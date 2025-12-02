package cn.bugstack.infrastructure.mq.listener;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 热点商品订单创建本地事务监听器（Infrastructure层）
 * 
 * 对标 NFTurbo 的 OrderCreateTransactionListener（newBuyPlus 场景）
 * 
 * 本地事务执行：
 * 1. Redis 商品库存扣减
 * 2. 数据库商品库存扣减
 * 3. 订单创建（不创建队伍，不做拼团）
 * 
 * ⚠️ 不做：
 * - 队伍库存扣减
 * - 队伍创建
 * 
 * @author liang.tian
 */
@Slf4j
@Component
@RocketMQTransactionListener
public class HotGoodsOrderCreateTransactionListener implements RocketMQLocalTransactionListener {

    // 商品库存键前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    @Resource
    private IRedisAdapter redisAdapter;

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;

    @Override
    @SuppressWarnings("rawtypes")
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        HotGoodsOrderAggregate aggregate = null;
        boolean goodsStockDecreased = false;
        boolean dbStockDecreased = false;
        
        try {
            aggregate = (HotGoodsOrderAggregate) arg;
            String userId = aggregate.getUserEntity().getUserId();
            String orderId = aggregate.getOrderId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

            String identifier = buildIdentifier(userId, orderId);

            // 1. 扣减商品库存（Redis 预扣减）
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;

            Long goodsStockResult = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
            if (goodsStockResult == null || goodsStockResult < 0) {
                log.warn("热点商品-事务预扣减商品库存失败: activityId={}, goodsId={}, orderId={}", activityId, goodsId, orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            goodsStockDecreased = true;
            log.info("热点商品-事务预扣减商品库存成功: activityId={}, goodsId={}, orderId={}, 剩余库存={}",
                    activityId, goodsId, orderId, goodsStockResult);

            // 2. 扣减数据库库存（在本地事务中同步执行）
            boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1, orderId, userId);
            if (!decreaseResult) {
                log.error("热点商品-数据库库存扣减失败: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
                // 回滚 Redis 库存
                rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            dbStockDecreased = true;
            log.info("热点商品-数据库库存扣减成功: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);

            // 3. 创建订单（在本地事务中同步执行，不创建队伍）
            try {
                MarketPayOrderEntity orderEntity = tradeRepository.lockHotGoodsOrder(aggregate);
                log.info("热点商品-订单创建成功: orderId={}", orderId);
                
                // 所有操作成功，提交事务
                return RocketMQLocalTransactionState.COMMIT;
            } catch (AppException e) {
                log.warn("热点商品-订单创建失败，回滚所有库存: orderId={}, code={}", orderId, e.getCode());
                // 回滚数据库库存
                if (dbStockDecreased) {
                    try {
                        skuRepository.releaseSkuStock(activityId, goodsId, 1);
                        log.info("热点商品-回滚数据库库存成功: orderId={}", orderId);
                    } catch (Exception ex) {
                        log.error("热点商品-回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                // 回滚 Redis 库存
                rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            log.error("热点商品-RocketMQ 本地事务执行失败: orderId={}", 
                    aggregate != null ? aggregate.getOrderId() : "unknown", e);
            
            // 异常时回滚所有已扣减的库存
            if (aggregate != null) {
                String userId = aggregate.getUserEntity().getUserId();
                String orderId = aggregate.getOrderId();
                Long activityId = aggregate.getPayActivityEntity().getActivityId();
                String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
                
                String identifier = buildIdentifier(userId, orderId);
                String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
                String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
                
                // 回滚数据库库存
                if (dbStockDecreased) {
                    try {
                        skuRepository.releaseSkuStock(activityId, goodsId, 1);
                    } catch (Exception ex) {
                        log.error("热点商品-异常回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                
                // 回滚 Redis 库存
                if (goodsStockDecreased) {
                    rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
                }
            }
            
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String orderId = "unknown";
        try {
            // 1. 解析消息
            String payload = new String((byte[]) message.getPayload());
            HotGoodsOrderAggregate aggregate = JSON.parseObject(payload, HotGoodsOrderAggregate.class);

            orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();

            // ⭐ 参考 NFTurbo：直接查询订单状态
            MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
            
            if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                // 订单已创建成功，说明本地事务已成功
                log.info("热点商品-事务回查-订单已创建成功: orderId={}, status={}", orderId, order.getTradeOrderStatusEnumVO());
                return RocketMQLocalTransactionState.COMMIT;
            }

            // 订单不存在或状态不正确，检查 Redis 流水作为辅助判断
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String identifier = buildIdentifier(userId, orderId);

            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsLogEntry = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);

            if (goodsLogEntry == null) {
                // Redis 流水不存在，说明本地事务失败或未执行
                log.warn("热点商品-事务回查-订单不存在且Redis流水不存在，本地事务失败: orderId={}", orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // Redis 流水存在但订单不存在，可能是订单创建失败
            log.warn("热点商品-事务回查-Redis流水存在但订单不存在，可能订单创建失败: orderId={}", orderId);
            return RocketMQLocalTransactionState.ROLLBACK;

        } catch (Exception e) {
            log.error("热点商品-事务回查异常，返回UNKNOWN稍后重试: orderId={}, error={}", orderId, e.getMessage(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 回滚商品库存
     */
    private void rollbackGoodsStock(String goodsStockKey, String goodsStockLogKey, String identifier, String orderId) {
        try {
            String rollbackIdentifier = "ROLLBACK_" + identifier;
            Long rollbackResult = redisAdapter.increaseStockWithLog(
                    goodsStockKey,
                    goodsStockLogKey,
                    rollbackIdentifier,
                    1
            );
            log.info("热点商品-回滚商品库存成功: orderId={}, 回滚后库存={}", orderId, rollbackResult);
        } catch (Exception e) {
            log.error("热点商品-回滚商品库存失败: orderId={}", orderId, e);
        }
    }

    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

