package cn.bugstack.domain.trade.service.normal;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.NormalGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.service.INormalGoodsTradeService;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.types.utils.SnowflakeIdUtil;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.annotation.Resource;

/**
 * 普通商品下单服务实现（Domain层）
 * 
 * 完全对标 NFTurbo 的 normalBuy（TCC 模式）
 * 
 * 流程：
 * 1. Try 阶段：
 *    - tryDecreaseInventory（尝试扣减库存，Redis 预扣减）
 *    - tryOrder（尝试创建订单，状态为 TRY）
 *    - 如果失败，发送 normalBuyCancel 消息进行补偿
 * 
 * 2. Confirm 阶段：
 *    - confirmDecreaseInventory（确认扣减库存，真正扣减数据库）
 *    - confirmOrder（确认订单，状态改为 CONFIRM）
 *    - 最多重试2次
 *    - 如果失败，发送 normalBuyPreCancel 消息（延迟消息）进行补偿
 * 
 * 特点：
 * 1. 保留拼团玩法
 * 2. 使用 TCC 模式保证一致性
 * 3. 通过责任链判断是否需要拼团
 * 
 * @author liang.tian
 */
@Slf4j
@Service
public class NormalGoodsTradeService implements INormalGoodsTradeService {

    // 最大重试次数（Confirm 阶段）
    private static final int MAX_RETRY_TIMES = 2;

    // 消息队列 Topic（普通商品补偿）
    private static final String NORMAL_GOODS_ORDER_CANCEL_BINDING = "normalGoodsOrderCancel-out-0";
    private static final String NORMAL_GOODS_ORDER_PRE_CANCEL_BINDING = "normalGoodsOrderPreCancel-out-0";

    @Resource
    private ITradeRepository repository;

    @Resource(name = "normalGoodsTradeRuleFilter")
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> normalGoodsTradeRuleFilter;

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private IMessageProducer messageProducer;

    @Override
    public MarketPayOrderEntity lockNormalGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("普通商品下单-锁定订单: userId={}, activityId={}, goodsId={}, teamId={}", 
                userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId(), payActivityEntity.getTeamId());

        // 1. 交易规则过滤（包含拼团相关过滤）
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = normalGoodsTradeRuleFilter.apply(
                TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId()) // 普通商品支持拼团，teamId 可能不为空
                        .goodsId(payDiscountEntity.getGoodsId())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext()
        );

        // 已参与拼团量 - 用于构建数据库唯一索引使用，确保用户只能在一个活动上参与固定的次数
        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();

        // 队伍的目标量（拼团相关）
        Integer targetCount = tradeLockRuleFilterBackEntity.getTargetCount();

        // 生成订单号（雪花算法）
        String orderId = SnowflakeIdUtil.nextIdStr();

        // 2. 构建聚合对象（包含拼团相关字段）
        NormalGoodsOrderAggregate normalGoodsOrderAggregate = NormalGoodsOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .orderId(orderId)
                .teamId(payActivityEntity.getTeamId()) // 拼团相关
                .targetCount(targetCount) // 拼团相关
                .build();

        // 3. Try 阶段
        boolean isTrySuccess = true;
        try {
            // 3.1 尝试扣减库存（Redis 预扣减，不扣减数据库）
            boolean inventoryResult = skuRepository.tryDecreaseInventory(
                    payActivityEntity.getActivityId(),
                    payDiscountEntity.getGoodsId(),
                    1,
                    orderId,
                    userEntity.getUserId()
            );
            Assert.isTrue(inventoryResult, "tryDecreaseInventory failed");

            // 3.2 尝试创建订单（状态为 TRY）
            MarketPayOrderEntity tryOrderResult = repository.tryOrder(normalGoodsOrderAggregate);
            Assert.notNull(tryOrderResult, "tryOrder failed");
        } catch (Exception e) {
            isTrySuccess = false;
            log.error("普通商品下单-Try阶段失败: orderId={}, error={}", orderId, e.getMessage(), e);
        }

        // 4. Try 失败，发送【废单消息】，异步进行逆向补偿
        if (!isTrySuccess) {
            // 回滚拼团库存（如果已扣减）
            rollbackTeamStockIfNeeded(payActivityEntity, tradeLockRuleFilterBackEntity);
            
            messageProducer.sendMessage(
                    NORMAL_GOODS_ORDER_CANCEL_BINDING,
                    orderId,
                    JSON.toJSONString(normalGoodsOrderAggregate)
            );
            throw new RuntimeException("订单创建失败（Try阶段失败）");
        }

        // 5. Confirm 阶段
        boolean isConfirmSuccess = false;
        int retryConfirmCount = 0;

        // 最大努力执行，失败最多尝试2次
        while (!isConfirmSuccess && retryConfirmCount < MAX_RETRY_TIMES) {
            try {
                // 5.1 确认扣减库存（真正扣减数据库库存）
                boolean confirmInventoryResult = skuRepository.confirmDecreaseInventory(
                        payActivityEntity.getActivityId(),
                        payDiscountEntity.getGoodsId(),
                        1,
                        orderId
                );
                Assert.isTrue(confirmInventoryResult, "confirmDecreaseInventory failed");

                // 5.2 确认订单（将订单状态从 TRY 改为 CONFIRM）
                boolean confirmOrderResult = repository.confirmOrder(orderId);
                Assert.isTrue(confirmOrderResult, "confirmOrder failed");

                isConfirmSuccess = true;
            } catch (Exception e) {
                retryConfirmCount++;
                isConfirmSuccess = false;
                log.error("普通商品下单-Confirm阶段失败: orderId={}, retryCount={}, error={}", 
                        orderId, retryConfirmCount, e.getMessage(), e);
            }
        }

        // 6. Confirm 失败，发送【疑似废单消息】进行延迟检查
        if (!isConfirmSuccess) {
            // 回滚拼团库存（如果已扣减）
            rollbackTeamStockIfNeeded(payActivityEntity, tradeLockRuleFilterBackEntity);
            
            messageProducer.sendDelayMessage(
                    NORMAL_GOODS_ORDER_PRE_CANCEL_BINDING,
                    orderId,
                    JSON.toJSONString(normalGoodsOrderAggregate),
                    1 // 延迟1分钟
            );
            throw new RuntimeException("订单创建失败（Confirm阶段失败）");
        }

        // 7. 查询订单并返回
        MarketPayOrderEntity order = repository.queryMarketPayOrderEntityByOrderId(userEntity.getUserId(), orderId);
        if (order == null) {
            log.error("普通商品下单-订单查询失败: orderId={}", orderId);
            throw new RuntimeException("订单查询失败");
        }

        log.info("普通商品下单-成功: orderId={}, status={}", orderId, order.getTradeOrderStatusEnumVO());
        return order;
    }

    /**
     * 回滚拼团库存（如果需要）
     * 
     * 在Try失败或Confirm失败时调用，回滚在责任链中已扣减的拼团库存
     */
    private void rollbackTeamStockIfNeeded(PayActivityEntity payActivityEntity, TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity) {
        try {
            String teamId = payActivityEntity.getTeamId();
            String recoveryTeamStockKey = tradeLockRuleFilterBackEntity != null ? tradeLockRuleFilterBackEntity.getRecoveryTeamStockKey() : null;
            Integer validTime = payActivityEntity.getValidTime();

            // 只有在teamId不为空且recoveryTeamStockKey不为空时，才需要回滚拼团库存
            // 因为teamId为空时，TeamStockOccupyRuleFilter不会扣减拼团库存
            if (StringUtils.isNotBlank(teamId) && StringUtils.isNotBlank(recoveryTeamStockKey) && validTime != null) {
                repository.recoveryTeamStock(recoveryTeamStockKey, validTime);
                log.info("普通商品下单-回滚拼团库存成功: teamId={}, recoveryTeamStockKey={}", teamId, recoveryTeamStockKey);
            } else {
                log.debug("普通商品下单-无需回滚拼团库存: teamId={}, recoveryTeamStockKey={}", teamId, recoveryTeamStockKey);
            }
        } catch (Exception e) {
            log.error("普通商品下单-回滚拼团库存失败: teamId={}", payActivityEntity.getTeamId(), e);
            // 不回滚失败不影响主流程，只记录日志
        }
    }
}
