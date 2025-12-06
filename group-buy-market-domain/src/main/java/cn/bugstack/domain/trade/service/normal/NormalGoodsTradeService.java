package cn.bugstack.domain.trade.service.normal;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
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

    @Resource
    private IRedisAdapter redisAdapter;

    // 商品库存 Redis 前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";
    // 队伍库存 Redis 前缀
    private static final String TEAM_STOCK_KEY_PREFIX = "group_buy_market_team_stock_key_";
    private static final String TEAM_STOCK_LOG_KEY_PREFIX = "group_buy_market_team_stock_log_";

    @Override
    public MarketPayOrderEntity lockNormalGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("普通商品下单-锁定订单: userId={}, activityId={}, goodsId={}, teamId={}", 
                userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId(), payActivityEntity.getTeamId());

        // 1. 交易规则过滤（包含拼团相关过滤）
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = normalGoodsTradeRuleFilter.apply(
                TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId())
                        .goodsId(payDiscountEntity.getGoodsId())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext()
        );

        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();
        Integer targetCount = tradeLockRuleFilterBackEntity.getTargetCount();
        String orderId = SnowflakeIdUtil.nextIdStr();

        // 2. 构建聚合对象
        NormalGoodsOrderAggregate normalGoodsOrderAggregate = NormalGoodsOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .orderId(orderId)
                .teamId(payActivityEntity.getTeamId())
                .targetCount(targetCount)
                .build();

        // 3. Try 阶段
        boolean isTrySuccess = true;
        String teamId = payActivityEntity.getTeamId();
        try {
            // 3.1 尝试扣减库存（Redis 预扣减，同时增加队伍人数，不扣减数据库）
            // 对标 newBuyPlus：在 Lua 脚本中同时扣减商品库存和增加队伍人数
            String identifier = buildIdentifier(userEntity.getUserId(), orderId);
            Long activityId = payActivityEntity.getActivityId();
            String goodsId = payDiscountEntity.getGoodsId();
            
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            
            String teamStockKey = null;
            String teamStockLogKey = null;
            if (teamId != null && !teamId.trim().isEmpty() && targetCount != null && targetCount > 0) {
                teamStockKey = TEAM_STOCK_KEY_PREFIX + activityId + "_" + teamId;
                teamStockLogKey = TEAM_STOCK_LOG_KEY_PREFIX + activityId + "_" + teamId;
            }
            
            // 使用新的原子操作：同时扣减商品库存和增加队伍人数
            IRedisAdapter.StockDecreaseResult stockResult = redisAdapter.decreaseGoodsStockAndIncreaseTeamStock(
                    goodsStockKey, goodsStockLogKey,
                    teamStockKey, teamStockLogKey,
                    identifier, 1, targetCount != null ? targetCount : 0
            );
            
            if (!stockResult.isSuccess()) {
                String errorCode = stockResult.getErrorCode();
                if ("TEAM_FULL".equals(errorCode)) {
                    log.warn("普通商品下单-Try阶段失败-队伍已满: orderId={}, teamId={}", orderId, teamId);
                    throw new RuntimeException("队伍已满");
                } else if ("GOODS_STOCK_NOT_ENOUGH".equals(errorCode)) {
                    log.warn("普通商品下单-Try阶段失败-商品库存不足: orderId={}", orderId);
                    throw new RuntimeException("商品库存不足");
                } else {
                    log.warn("普通商品下单-Try阶段失败-Redis扣减失败: orderId={}, errorCode={}", orderId, errorCode);
                    throw new RuntimeException("Redis扣减失败: " + errorCode);
                }
            }
            
            log.info("普通商品下单-Try阶段-Redis扣减成功: orderId={}, 商品剩余库存={}, 队伍当前人数={}/{}",
                    orderId, stockResult.getGoodsRemainingStock(), 
                    stockResult.getTeamCurrentCount(), targetCount);

            // 3.2 尝试创建订单（状态为 TRY）
            boolean result = repository.tryOrder(normalGoodsOrderAggregate) != null;
            Assert.isTrue(result, "tryOrder failed");
        } catch (Exception e) {
            isTrySuccess = false;
            log.error("普通商品下单-Try阶段失败: orderId={}, error={}", orderId, e.getMessage(), e);
        }

        // 4. Try 失败，发送【废单消息】，异步进行逆向补偿
        if (!isTrySuccess) {
            // 消息监听：NormalGoodsOrderCancelListener
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

        // 最大努力执行，失败最多尝试2次（Dubbo也会有重试机制，在服务突然不可用、超时等情况下会重试2次）
        while (!isConfirmSuccess && retryConfirmCount < MAX_RETRY_TIMES) {
            try {
                // 5.1 确认扣减库存（真正扣减数据库库存，同时增加队伍人数）
                // 对标 newBuyPlus：在同一个事务中扣减商品库存和增加队伍人数
                boolean result = skuRepository.decreaseSkuStockAndIncreaseTeamCount(
                        payActivityEntity.getActivityId(),
                        payDiscountEntity.getGoodsId(),
                        1,
                        teamId, // 如果teamId不为空，会在同一个事务中增加队伍人数
                        orderId,
                        userEntity.getUserId()
                );
                Assert.isTrue(result, "decreaseSkuStockAndIncreaseTeamCount failed");

                // 5.2 确认订单（将订单状态从 TRY 改为 CONFIRM）
                result = repository.confirmOrder(orderId);
                Assert.isTrue(result, "confirmOrder failed");

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
            // 消息监听：NormalGoodsOrderPreCancelListener
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
     * 构建库存扣减标识符
     */
    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }

}
