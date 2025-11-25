package cn.bugstack.infrastructure.mq.listener;

import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.PayActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * RocketMQ事务监听器：预扣减库存
 */
@Slf4j
@Component
@RocketMQTransactionListener
public class OrderCreateTransactionListener implements RocketMQLocalTransactionListener {

    // 队伍库存键前缀
    private static final String TEAM_STOCK_KEY_PREFIX = "group_buy_market_team_stock_key_";
    private static final String TEAM_STOCK_LOG_KEY_PREFIX = "group_buy_market_team_stock_log_";

    // 商品库存键前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    @Resource
    private IRedisAdapter redisAdapter;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        try {
            GroupBuyOrderAggregate aggregate = (GroupBuyOrderAggregate) arg;
            String teamId = aggregate.getPayActivityEntity().getTeamId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();

            String identifier = buildIdentifier(userId, orderId);

            // 1. 扣减商品库存（先扣商品库存，防止商品超卖）
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;

            Long goodsStockResult = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
            if (goodsStockResult == null || goodsStockResult < 0) {
                log.warn("事务预扣减商品库存失败: activityId={}, goodsId={}, orderId={}", activityId, goodsId, orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            log.info("事务预扣减商品库存成功: activityId={}, goodsId={}, orderId={}, 剩余库存={}", activityId, goodsId, orderId, goodsStockResult);

            // 2. 扣减队伍库存（如果队伍库存扣减失败，需要回滚商品库存）
            if (StringUtils.isNotBlank(teamId)) {
                String teamStockKey = TEAM_STOCK_KEY_PREFIX + teamId;
                String teamStockLogKey = TEAM_STOCK_LOG_KEY_PREFIX + teamId;

                Long teamStockResult = redisAdapter.decreaseStockWithLog(teamStockKey, teamStockLogKey, identifier, 1);
                if (teamStockResult == null || teamStockResult < 0) {
                    log.warn("事务预扣减队伍库存失败: teamId={}, orderId={}", teamId, orderId);
                    // 回滚商品库存
                    redisAdapter.increaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
                    return RocketMQLocalTransactionState.ROLLBACK;
                }
                log.info("事务预扣减队伍库存成功: teamId={}, orderId={}, 剩余库存={}", teamId, orderId, teamStockResult);
            }

            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("事务预扣减库存异常", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        try {
            String payload = new String((byte[]) message.getPayload());
            GroupBuyOrderAggregate aggregate = JSON.parseObject(payload, GroupBuyOrderAggregate.class);

            String orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

            String identifier = buildIdentifier(userId, orderId);

            // 检查商品库存扣减日志
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsLogEntry = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);

            if (goodsLogEntry == null) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("事务回查异常", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

