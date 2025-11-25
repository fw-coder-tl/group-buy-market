package cn.bugstack.domain.trade.service.lock;

import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.model.valobj.GroupBuyProgressVO;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.domain.trade.service.ITradeLockOrderService;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.types.exception.AppException;
import cn.bugstack.types.utils.SnowflakeIdUtil;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * @author liang.tian
 */
@Slf4j
@Service
public class TradeLockOrderService implements ITradeLockOrderService {

    @Resource
    private ITradeRepository repository;
    @Resource
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter;
    @Resource
    private IMessageProducer messageProducer;

    @Resource
    private IRedisAdapter redisAdapter;

    // 旁路验证线程池
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(10);

    private static final String ORDER_CREATE_BINDING = "orderCreate-out-0";

    // 商品库存redis的前缀
    private static final String GOODS_STOCK_KEY = "group_buy_market_goods_stock_key_";
    // 商品库存流水的前缀
    private static final String GOODS_STOCK_LOG_KEY = "group_buy_market_goods_stock_log_key_";

    @Override
    public MarketPayOrderEntity queryNoPayMarketPayOrderByOutTradeNo(String userId, String outTradeNo) {
        log.info("拼团交易-查询未支付营销订单:{} outTradeNo:{}", userId, outTradeNo);
        return repository.queryMarketPayOrderEntityByOutTradeNo(userId, outTradeNo);
    }

    @Override
    public GroupBuyProgressVO queryGroupBuyProgress(String teamId) {
        log.info("拼团交易-查询拼单进度:{}", teamId);
        return repository.queryGroupBuyProgress(teamId);
    }

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("拼团交易-锁定营销优惠支付订单:{} activityId:{} goodsId:{}", userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        // 1.交易规则过滤
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = tradeRuleFilter.apply(TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId())
                        .goodsId(payDiscountEntity.getGoodsId())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext());

        // 已参与拼团量 - 用于构建数据库唯一索引使用，确保用户只能在一个活动上参与固定的次数
        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();

        // 获取订单号
        String orderId = tradeLockRuleFilterBackEntity.getOrderId();
        if (StringUtils.isBlank(orderId)) {
            log.error("交易规则过滤未返回订单号: teamId={}", payActivityEntity.getTeamId());
            throw new AppException("订单创建失败");
        }

        // 5.构建聚合对象（包含orderId）
        GroupBuyOrderAggregate groupBuyOrderAggregate = GroupBuyOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .orderId(orderId)
                .build();

        // 6. 构建库存扣减标识（使用orderId代替outTradeNo，更安全可靠）
        String teamId = payActivityEntity.getTeamId();
        String goodsStockLogKey = GOODS_STOCK_LOG_KEY + "_" + payActivityEntity.getActivityId() + "_" + payDiscountEntity.getGoodsId();
        String identifier = buildIdentifier(userEntity.getUserId(), orderId);


        // 7. ⭐ 发送RocketMQ事务消息 (本地事务中预扣减库存)
        boolean sendResult = messageProducer.sendOrderCreateMessage(
                ORDER_CREATE_BINDING,   // 创建订单绑定
                orderId,  // 使用orderId作为消息唯一标识
                JSON.toJSONString(groupBuyOrderAggregate),
                groupBuyOrderAggregate
        );

        if (!sendResult) {
            log.error("发送RocketMQ事务消息失败: teamId={}, orderId={}", teamId, orderId);
            throw new RuntimeException("订单创建失败");
        }

        // 8. ⭐ 确认预扣减结果
        String decreaseLog = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
        if (decreaseLog == null) {
            log.error("未查询到库存扣减流水,预扣减失败: teamId={}, orderId={}", teamId, orderId);
            throw new RuntimeException("库存预扣减失败");
        }

        // 9. ⭐ 旁路验证 (延迟3秒检查数据库)
        bypassVerify(goodsStockLogKey, identifier, teamId, orderId, userEntity.getUserId());

        // 10. 返回预订单
        return MarketPayOrderEntity.builder()
                .orderId(orderId)
                .originalPrice(payDiscountEntity.getOriginalPrice())
                .deductionPrice(payDiscountEntity.getDeductionPrice())
                .payPrice(payDiscountEntity.getPayPrice())
                .tradeOrderStatusEnumVO(TradeOrderStatusEnumVO.CREATE)
                .teamId(teamId)
                .build();

    }

    /**
     * 旁路验证: 延迟检查Redis流水与DB的一致性
     */
    private void bypassVerify(String goodsStockLogKey, String identifier, String teamId, String orderId, String userId) {
        scheduler.schedule(() -> {
            try {
                log.info("旁路验证-开始: teamId={}, orderId={}", teamId, orderId);

                // 1. 查询Redis流水
                String redisLog = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
                if (redisLog == null) {
                    log.warn("旁路验证-Redis流水不存在: teamId={}, orderId={}", teamId, orderId);
                    return;
                }

                // 2. 查询数据库订单（使用outTradeNo查询，因为数据库查询接口使用的是outTradeNo）
                MarketPayOrderEntity order = repository.queryMarketPayOrderEntityByOrderId(userId, orderId);

                if (order != null && orderId.equals(order.getOrderId())) {
                    redisAdapter.removeStockDecreaseLog(goodsStockLogKey, identifier);
                    log.info("旁路验证-成功: teamId={}, orderId={}", teamId, orderId);
                } else {
                    log.warn("旁路验证-未找到订单或orderId不匹配: teamId={}, orderId={}", teamId, orderId);
                }

            } catch (Exception e) {
                log.error("旁路验证-异常: teamId={}, orderId={}", teamId, orderId, e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * 构建库存扣减标识符（使用orderId代替outTradeNo，更安全可靠）
     */
    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
 

}
