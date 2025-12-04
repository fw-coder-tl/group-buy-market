package cn.bugstack.infrastructure.mq.listener;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.NormalGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 普通商品订单创建本地事务监听器（Infrastructure层）
 * 
 * 对标 NFTurbo 的 OrderCreateTransactionListener（普通商品场景，但使用事务消息）
 * 
 * 本地事务执行：
 * 1. Redis 商品库存扣减
 * 2. Redis 队伍库存扣减（如果需要，即 teamId 不为空）
 * 3. 数据库商品库存扣减
 * 4. 订单创建（创建队伍，如果需要）
 * 
 * ⚠️ 注意：虽然对标 normalBuy，但实现方式使用 RocketMQ 事务消息（类似 newBuyPlus）
 * 这样可以保证 Redis 扣减、数据库扣减、订单创建的一致性
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class NormalGoodsOrderCreateTransactionListener implements TransactionListener {

    // 队伍库存键前缀
    private static final String TEAM_STOCK_KEY_PREFIX = "group_buy_market_team_stock_key_";
    private static final String TEAM_STOCK_LOG_KEY_PREFIX = "group_buy_market_team_stock_log_";

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
    public LocalTransactionState executeLocalTransaction(Message message, Object o) {
        NormalGoodsOrderAggregate aggregate = null;
        boolean teamStockDecreased = false;
        boolean dbStockDecreased = false;
        
        try {
            // 从 message body 中解析参数（参考 NFTurbo）
            MessageBody messageBody = JSON.parseObject(message.getBody(), MessageBody.class);
            aggregate = JSON.parseObject(messageBody.getBody(), NormalGoodsOrderAggregate.class);
            String userId = aggregate.getUserEntity().getUserId();
            String orderId = aggregate.getOrderId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String teamId = aggregate.getTeamId();
            Integer targetCount = aggregate.getTargetCount();

            String identifier = buildIdentifier(userId, orderId);

            // 1. 扣减商品库存（Redis 预扣减）
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;

            Long goodsStockResult = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
            if (goodsStockResult == null || goodsStockResult < 0) {
                log.warn("普通商品-事务预扣减商品库存失败: activityId={}, goodsId={}, orderId={}", activityId, goodsId, orderId);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
            log.info("普通商品-事务预扣减商品库存成功: activityId={}, goodsId={}, orderId={}, 剩余库存={}",
                    activityId, goodsId, orderId, goodsStockResult);

            // 2. 扣减队伍库存（如果有 teamId 且 targetCount > 0）
            String teamStockKey = null;
            String teamStockLogKey = null;
            if (StringUtils.isNotBlank(teamId) && targetCount != null && targetCount > 0) {
                teamStockKey = TEAM_STOCK_KEY_PREFIX + activityId + "_" + teamId;
                teamStockLogKey = TEAM_STOCK_LOG_KEY_PREFIX + activityId + "_" + teamId;

                Long teamStockResult = redisAdapter.decreaseTeamStockDynamically(
                        teamStockKey, teamStockLogKey, identifier, targetCount);

                if (teamStockResult == null || teamStockResult < 0) {
                    if (teamStockResult == -1L) {
                        log.warn("普通商品-事务预扣减队伍库存失败-队伍已满: teamId={}, orderId={}, targetCount={}",
                                teamId, orderId, targetCount);
                    } else if (teamStockResult == -2L) {
                        log.warn("普通商品-事务预扣减队伍库存失败-重复操作: teamId={}, orderId={}", teamId, orderId);
                    } else {
                        log.warn("普通商品-事务预扣减队伍库存失败: teamId={}, orderId={}", teamId, orderId);
                    }
                    // 队伍库存扣减失败，回滚商品库存
                    rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
                teamStockDecreased = true;
                log.info("普通商品-事务预扣减队伍库存成功: teamId={}, orderId={}, 当前人数={}/{}",
                        teamId, orderId, teamStockResult, targetCount);
            } else {
                log.info("普通商品-首次开团或无需拼团，无需扣减队伍库存: orderId={}", orderId);
            }

            // 3. 扣减数据库库存（在本地事务中同步执行）
            boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1, orderId, userId);
            if (!decreaseResult) {
                log.error("普通商品-数据库库存扣减失败: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
                // 回滚所有已扣减的库存
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
            dbStockDecreased = true;
            log.info("普通商品-数据库库存扣减成功: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);

            // 4. 创建订单（在本地事务中同步执行，创建队伍，如果需要）
            try {
                MarketPayOrderEntity orderEntity = tradeRepository.lockNormalGoodsOrder(aggregate);
                log.info("普通商品-订单创建成功: orderId={}, teamId={}", orderId, orderEntity.getTeamId());
                
                // 所有操作成功，提交事务
                return LocalTransactionState.COMMIT_MESSAGE;
            } catch (AppException e) {
                log.warn("普通商品-订单创建失败，回滚所有库存: orderId={}, code={}", orderId, e.getCode());
                // 回滚数据库库存
                if (dbStockDecreased) {
                    try {
                        skuRepository.releaseSkuStock(activityId, goodsId, 1);
                        log.info("普通商品-回滚数据库库存成功: orderId={}", orderId);
                    } catch (Exception ex) {
                        log.error("普通商品-回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                // 回滚 Redis 库存（商品库存 + 队伍库存）
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        } catch (Exception e) {
            log.error("普通商品-RocketMQ 本地事务执行失败: orderId={}", 
                    aggregate != null ? aggregate.getOrderId() : "unknown", e);
            
            // 异常时回滚所有已扣减的库存
            if (aggregate != null) {
                String userId = aggregate.getUserEntity().getUserId();
                String orderId = aggregate.getOrderId();
                Long activityId = aggregate.getPayActivityEntity().getActivityId();
                String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
                String teamId = aggregate.getTeamId();
                
                String identifier = buildIdentifier(userId, orderId);
                String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
                String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
                
                String teamStockKey = null;
                String teamStockLogKey = null;
                if (StringUtils.isNotBlank(teamId)) {
                    teamStockKey = TEAM_STOCK_KEY_PREFIX + activityId + "_" + teamId;
                    teamStockLogKey = TEAM_STOCK_LOG_KEY_PREFIX + activityId + "_" + teamId;
                }
                
                // 回滚数据库库存
                if (dbStockDecreased) {
                    try {
                        skuRepository.releaseSkuStock(activityId, goodsId, 1);
                    } catch (Exception ex) {
                        log.error("普通商品-异常回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                
                // 回滚 Redis 库存
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
            }
            
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
        String orderId = "unknown";
        try {
            // 1. 解析消息（参考 NFTurbo）
            MessageBody messageBody = JSON.parseObject(new String(messageExt.getBody()), MessageBody.class);
            NormalGoodsOrderAggregate aggregate = JSON.parseObject(messageBody.getBody(), NormalGoodsOrderAggregate.class);

            orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();

            // ⭐ 参考 NFTurbo：直接查询订单状态
            MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
            
            if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                // 订单已创建成功，说明本地事务已成功
                log.info("普通商品-事务回查-订单已创建成功: orderId={}, status={}", orderId, order.getTradeOrderStatusEnumVO());
                return LocalTransactionState.COMMIT_MESSAGE;
            }

            // 订单不存在或状态不正确，检查 Redis 流水作为辅助判断
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String identifier = buildIdentifier(userId, orderId);

            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsLogEntry = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);

            if (goodsLogEntry == null) {
                // Redis 流水不存在，说明本地事务失败或未执行
                log.warn("普通商品-事务回查-订单不存在且Redis流水不存在，本地事务失败: orderId={}", orderId);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }

            // Redis 流水存在但订单不存在，可能是订单创建失败
            log.warn("普通商品-事务回查-Redis流水存在但订单不存在，可能订单创建失败: orderId={}", orderId);
            return LocalTransactionState.ROLLBACK_MESSAGE;

        } catch (Exception e) {
            log.error("普通商品-事务回查异常，返回ROLLBACK: orderId={}, error={}", orderId, e.getMessage(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    /**
     * 回滚商品库存
     */
    private void rollbackGoodsStock(String goodsStockKey, String goodsStockLogKey, String identifier, String orderId) {
        try {
            String rollbackIdentifier = "ROLLBACK_MESSAGE_" + identifier;
            Long rollbackResult = redisAdapter.increaseStockWithLog(
                    goodsStockKey,
                    goodsStockLogKey,
                    rollbackIdentifier,
                    1
            );
            log.info("普通商品-回滚商品库存成功: orderId={}, 回滚后库存={}", orderId, rollbackResult);
        } catch (Exception e) {
            log.error("普通商品-回滚商品库存失败: orderId={}", orderId, e);
        }
    }

    /**
     * 回滚队伍库存（队伍库存的扣减实际上是 +1，所以回滚是 -1）
     */
    private void rollbackTeamStock(String teamStockKey, String teamStockLogKey, String identifier, String orderId) {
        try {
            String rollbackIdentifier = "ROLLBACK_MESSAGE_" + identifier;
            // 队伍库存的扣减是 +1（增加队伍人数），所以回滚是 -1（减少队伍人数）
            Long rollbackResult = redisAdapter.decreaseStockWithLog(
                    teamStockKey,
                    teamStockLogKey,
                    rollbackIdentifier,
                    1
            );
            
            if (rollbackResult != null && rollbackResult >= 0) {
                log.info("普通商品-回滚队伍库存成功: orderId={}, teamStockKey={}, 回滚后人数={}", 
                        orderId, teamStockKey, rollbackResult);
            } else {
                log.warn("普通商品-回滚队伍库存失败-库存不足或已为0: orderId={}, teamStockKey={}, result={}", 
                        orderId, teamStockKey, rollbackResult);
            }
        } catch (Exception e) {
            log.error("普通商品-回滚队伍库存失败: orderId={}, teamStockKey={}", orderId, teamStockKey, e);
        }
    }

    /**
     * 回滚所有库存（商品库存 + 队伍库存）
     */
    private void rollbackAllStocks(String goodsStockKey, String goodsStockLogKey,
                                   String teamStockKey, String teamStockLogKey,
                                   String identifier, String orderId, boolean teamStockDecreased) {
        // 回滚商品库存
        if (goodsStockKey != null && goodsStockLogKey != null) {
            rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
        }
        
        // 回滚队伍库存（如果已扣减）
        if (teamStockDecreased && teamStockKey != null && teamStockLogKey != null) {
            rollbackTeamStock(teamStockKey, teamStockLogKey, identifier, orderId);
        }
    }

    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

