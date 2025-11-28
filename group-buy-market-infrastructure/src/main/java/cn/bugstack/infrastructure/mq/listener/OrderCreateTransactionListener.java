package cn.bugstack.infrastructure.mq.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson2.JSON;
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

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        GroupBuyOrderAggregate aggregate = null;
        boolean goodsStockDecreased = false;
        boolean teamStockDecreased = false;
        boolean dbStockDecreased = false;
        
        try {
            aggregate = (GroupBuyOrderAggregate) arg;
            String userId = aggregate.getUserEntity().getUserId();
            String orderId = aggregate.getOrderId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String teamId = aggregate.getPayActivityEntity().getTeamId();
            Integer targetCount = aggregate.getTargetCount();

            String identifier = buildIdentifier(userId, orderId);

            // 1. 扣减商品库存（Redis 预扣减）
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;

            Long goodsStockResult = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
            if (goodsStockResult == null || goodsStockResult < 0) {
                log.warn("事务预扣减商品库存失败: activityId={}, goodsId={}, orderId={}", activityId, goodsId, orderId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            goodsStockDecreased = true; // 标记商品库存已扣减
            log.info("事务预扣减商品库存成功: activityId={}, goodsId={}, orderId={}, 剩余库存={}",
                    activityId, goodsId, orderId, goodsStockResult);

            // 2. 扣减队伍库存（如果有 teamId）
            String teamStockKey = null;
            String teamStockLogKey = null;
            if (StringUtils.isNotBlank(teamId) && targetCount != null && targetCount > 0) {
                teamStockKey = TEAM_STOCK_KEY_PREFIX + activityId + "_" + teamId;
                teamStockLogKey = TEAM_STOCK_LOG_KEY_PREFIX + activityId + "_" + teamId;

                Long teamStockResult = redisAdapter.decreaseTeamStockDynamically(
                        teamStockKey, teamStockLogKey, identifier, targetCount);

                if (teamStockResult == null || teamStockResult < 0) {
                    if (teamStockResult == -1L) {
                        log.warn("事务预扣减队伍库存失败-队伍已满: teamId={}, orderId={}, targetCount={}",
                                teamId, orderId, targetCount);
                    } else if (teamStockResult == -2L) {
                        log.warn("事务预扣减队伍库存失败-重复操作: teamId={}, orderId={}", teamId, orderId);
                    } else {
                        log.warn("事务预扣减队伍库存失败: teamId={}, orderId={}", teamId, orderId);
                    }
                    // 队伍库存扣减失败，回滚商品库存
                    rollbackGoodsStock(goodsStockKey, goodsStockLogKey, identifier, orderId);
                    return RocketMQLocalTransactionState.ROLLBACK;
                }
                teamStockDecreased = true; // 标记队伍库存已扣减
                log.info("事务预扣减队伍库存成功: teamId={}, orderId={}, 当前人数={}/{}",
                        teamId, orderId, teamStockResult, targetCount);
            } else {
                log.info("首次开团，无需扣减队伍库存: orderId={}", orderId);
            }

            // 3. 扣减数据库库存（参考 NFTurbo，在本地事务中同步执行）
            boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1, orderId, userId);
            if (!decreaseResult) {
                log.error("数据库库存扣减失败: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
                // 回滚所有已扣减的库存
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            dbStockDecreased = true; // 标记数据库库存已扣减
            log.info("数据库库存扣减成功: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);

            // 4. 创建订单（参考 NFTurbo，在本地事务中同步执行）
            try {
                MarketPayOrderEntity orderEntity = tradeRepository.lockMarketPayOrder(aggregate);
                log.info("订单创建成功: orderId={}, teamId={}", orderId, orderEntity.getTeamId());
                
                // 所有操作成功，提交事务
                return RocketMQLocalTransactionState.COMMIT;
            } catch (AppException e) {
                log.warn("订单创建失败，回滚所有库存: orderId={}, code={}", orderId, e.getCode());
                // 回滚数据库库存
                if (dbStockDecreased) {
                    try {
                        skuRepository.releaseSkuStock(activityId, goodsId, 1);
                        log.info("回滚数据库库存成功: orderId={}", orderId);
                    } catch (Exception ex) {
                        log.error("回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                // 回滚 Redis 库存（商品库存 + 队伍库存）
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            log.error("RocketMQ 本地事务执行失败: orderId={}", aggregate != null ? aggregate.getOrderId() : "unknown", e);
            
            // 异常时回滚所有已扣减的库存
            if (aggregate != null) {
                String userId = aggregate.getUserEntity().getUserId();
                String orderId = aggregate.getOrderId();
                Long activityId = aggregate.getPayActivityEntity().getActivityId();
                String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
                String teamId = aggregate.getPayActivityEntity().getTeamId();
                
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
                        log.error("异常回滚数据库库存失败: orderId={}", orderId, ex);
                    }
                }
                
                // 回滚 Redis 库存
                rollbackAllStocks(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey,
                        identifier, orderId, teamStockDecreased);
            }
            
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 回滚商品库存
     */
    private void rollbackGoodsStock(String goodsStockKey, String goodsStockLogKey, String identifier, String orderId) {
        try {
            // 生成回滚标识（避免与原始 identifier 冲突）
            String rollbackIdentifier = "ROLLBACK_" + identifier;

            Long rollbackResult = redisAdapter.increaseStockWithLog(
                    goodsStockKey,
                    goodsStockLogKey,
                    rollbackIdentifier,
                    1
            );

            log.info("回滚商品库存成功: orderId={}, 回滚后库存={}", orderId, rollbackResult);
        } catch (Exception e) {
            log.error("回滚商品库存失败: orderId={}", orderId, e);
        }
    }

    /**
     * 回滚队伍库存（队伍库存的扣减实际上是 +1，所以回滚是 -1）
     * 
     * 注意：decreaseTeamStockDynamically 是增加队伍人数（+1），所以回滚应该是减少队伍人数（-1）
     * 使用 decreaseStockWithLog 来实现回滚，减少 1 个队伍人数
     */
    private void rollbackTeamStock(String teamStockKey, String teamStockLogKey, String identifier, String orderId) {
        try {
            // 生成回滚标识（避免与原始 identifier 冲突）
            String rollbackIdentifier = "ROLLBACK_" + identifier;

            // 队伍库存的扣减是 +1（增加队伍人数），所以回滚是 -1（减少队伍人数）
            // 使用 decreaseStockWithLog 来减少队伍人数
            // decreaseStockWithLog 内部会检查库存是否足够，如果不足会返回错误，这里捕获异常即可
            Long rollbackResult = redisAdapter.decreaseStockWithLog(
                    teamStockKey,
                    teamStockLogKey,
                    rollbackIdentifier,
                    1
            );
            
            if (rollbackResult != null && rollbackResult >= 0) {
                log.info("回滚队伍库存成功: orderId={}, teamStockKey={}, 回滚后人数={}", 
                        orderId, teamStockKey, rollbackResult);
            } else {
                log.warn("回滚队伍库存失败-库存不足或已为0: orderId={}, teamStockKey={}, result={}", 
                        orderId, teamStockKey, rollbackResult);
            }
        } catch (Exception e) {
            log.error("回滚队伍库存失败: orderId={}, teamStockKey={}", orderId, teamStockKey, e);
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

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String orderId = "unknown";
        try {
            // 1. 解析消息
            String payload = new String((byte[]) message.getPayload());
            GroupBuyOrderAggregate aggregate = JSON.parseObject(payload, GroupBuyOrderAggregate.class);

            orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();

            // ⭐ 参考 NFTurbo：直接查询订单状态，而不是检查 Redis 流水
            // 因为订单已在本地事务中创建，如果订单存在且状态正确，说明本地事务成功
            MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
            
            if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                // 订单已创建成功，说明本地事务已成功
                log.info("事务回查-订单已创建成功: orderId={}, status={}", orderId, order.getTradeOrderStatusEnumVO());
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
                log.warn("事务回查-订单不存在且Redis流水不存在，本地事务失败: orderId={}, activityId={}, goodsId={}",
                        orderId, activityId, goodsId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // Redis 流水存在但订单不存在，可能是：
            // 1. 订单创建失败（数据库异常等）
            // 2. 订单创建成功但状态还未更新
            // 3. 网络延迟导致订单查询不到
            
            // ⭐ 参考 NFTurbo：如果流水存在但订单不存在，返回 ROLLBACK
            // 因为订单应该在本地事务中创建，如果不存在说明事务失败
            log.warn("事务回查-Redis流水存在但订单不存在，可能订单创建失败: orderId={}", orderId);
            return RocketMQLocalTransactionState.ROLLBACK;

        } catch (Exception e) {
            log.error("事务回查异常，返回UNKNOWN稍后重试: orderId={}, error={}", orderId, e.getMessage(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

