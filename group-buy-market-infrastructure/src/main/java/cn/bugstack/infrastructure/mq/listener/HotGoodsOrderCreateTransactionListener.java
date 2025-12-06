package cn.bugstack.infrastructure.mq.listener;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import cn.bugstack.infrastructure.mq.producer.StreamProducer;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 热点商品订单创建本地事务监听器（Infrastructure层）
 * 
 * 参考 NFTurbo 的 OrderCreateTransactionListener（newBuyPlus 场景）
 * 使用 Spring Cloud Stream + RocketMQ 的 TransactionListener 接口
 * 
 * 本地事务执行（参考 NFTurbo TradeApplicationService.newBuyPlus）：
 * 1. Redis 商品库存扣减（1次Redis操作）
 *    - 扣减失败时，查询流水检查是否真的失败
 *    - 如果流水存在，说明扣减成功（可能是网络延迟导致的假失败），继续执行
 *    - 如果流水不存在，说明真的扣减失败，回滚事务
 * 2. 订单创建（1次DB插入，创建时查询userTakeOrderCount）
 *    - 创建失败时，直接发送延迟消息，不查询数据库
 *    - 原因：可能存在"假失败"情况（订单实际已创建，但返回时网络超时/数据库异常）
 *    - 延迟消息会在30秒后再次检查订单状态，如果订单存在则补偿，不存在则回滚
 *
 * 不在本地事务中执行：
 * - 责任链校验（活动有效性、用户参与次数等）- 移至消息监听器
 * - 数据库库存扣减 - 移至消息监听器异步扣减（参考 NFTurbo：setSyncDecreaseInventory(false)）
 * - 队伍库存扣减 - 热点商品不做拼团
 * - 队伍创建 - 热点商品不做拼团
 * 
 * 重要说明：
 * - 参考 NFTurbo：本地事务中不扣减数据库库存，减少本地事务时间
 * - 数据库库存扣减在消息监听器（HotGoodsOrderCreateMessageListener）中异步执行
 * - 这样可以避免消息堆积，因为消息会被正常消费
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotGoodsOrderCreateTransactionListener implements TransactionListener {

    // 商品库存键前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    @Resource
    private IRedisAdapter redisAdapter;

    @Resource
    private ITradeRepository tradeRepository;
    
    @Resource
    private IMessageProducer messageProducer;

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object o) {
        HotGoodsOrderAggregate aggregate = null;
        
        try {
            // 从 message body 中解析参数（参考 NFTurbo）
            MessageBody messageBody = JSON.parseObject(message.getBody(), MessageBody.class);
            aggregate = JSON.parseObject(messageBody.getBody(), HotGoodsOrderAggregate.class);
            String userId = aggregate.getUserEntity().getUserId();
            String orderId = aggregate.getOrderId();
            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

            // 优化：移除责任链校验，减少本地事务IO操作（从3次减少到2次）
            // 责任链校验（活动有效性、用户参与次数等）将在消息监听器中执行
            // userTakeOrderCount 将在订单创建时查询（TradeRepository.lockHotGoodsOrder）

            String identifier = buildIdentifier(userId, orderId);
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;

            // 1. 扣减Redis库存（参考 NFTurbo TradeApplicationService.newBuyPlus）
            try {
                Long goodsStockResult = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
                if (goodsStockResult == null || goodsStockResult < 0) {
                    throw new RuntimeException("decrease stock failed, result=" + goodsStockResult);
                }
                log.info("热点商品-事务预扣减商品库存成功: activityId={}, goodsId={}, orderId={}, 剩余库存={}",
                        activityId, goodsId, orderId, goodsStockResult);
            } catch (Exception e) {
                // Redis扣减失败时，查询流水检查是否真的失败（参考 NFTurbo）
                // 如果流水存在，说明扣减成功（可能是网络延迟导致的假失败），继续执行
                log.warn("热点商品-Redis扣减失败，查询流水检查: orderId={}, error={}", orderId, e.getMessage());
                // 这里如果查询也失败，就只能旁路验证和对账来保证数据一致性
                String goodsLogEntry = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
                if (goodsLogEntry == null) {
                    // 流水不存在，说明真的扣减失败
                    log.error("热点商品-Redis扣减失败且流水不存在，回滚: orderId={}", orderId);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
                // 流水存在，说明扣减成功（假失败），继续执行
                log.info("热点商品-Redis扣减失败但流水存在（假失败），继续执行: orderId={}", orderId);
            }

            // 2. 创建订单
            try {
                tradeRepository.lockHotGoodsOrder(aggregate);
                log.info("热点商品-订单创建成功: orderId={}", orderId);
                
                // 所有操作成功，提交事务
                return LocalTransactionState.COMMIT_MESSAGE;
            } catch (Exception e) {
                // 订单创建失败时，直接发送延迟消息，不查询数据库（参考 NFTurbo）
                // 原因：可能存在"假失败"情况（订单实际已创建，但返回时网络超时/数据库异常）
                // 延迟消息会在30秒后再次检查订单状态，如果订单存在则补偿，不存在则回滚
                log.warn("热点商品-订单创建失败，发送延迟检查消息（疑似废单）: orderId={}, error={}", orderId, e.getMessage());
                
                // 发送延迟消息，检查发送结果
                // 如果发送失败：
                // 1. 记录错误日志（让运维知道）
                // 2. 依赖定时任务 InventoryCompensateJob 兜底处理（每30秒执行一次）
                // 3. 不能在这里直接回滚 Redis 库存，因为可能存在"假失败"情况（订单实际已创建）
                boolean sendResult = messageProducer.sendDelayMessage(
                        "hotGoodsOrderPreCancel-out-0",  // 使用正确的 bindingName（Producer）
                        orderId,
                        JSON.toJSONString(aggregate),
                        StreamProducer.DELAY_LEVEL_30_S // 延迟30秒
                );
                
                if (!sendResult) {
                    // 发送延迟消息失败，记录错误日志
                    // 依赖定时任务 InventoryCompensateJob 兜底处理：
                    // - 定时任务会扫描 Redis 库存扣减流水
                    // - 检查订单是否已落库
                    // - 如果订单未落库，回滚 Redis 库存
                    log.error("热点商品-发送延迟检查消息失败，依赖定时任务兜底处理: orderId={}, activityId={}, goodsId={}", 
                            orderId, activityId, goodsId);
                } else {
                    log.info("热点商品-延迟检查消息发送成功: orderId={}, 将在30秒后检查订单状态", orderId);
                }
                
                // 注意：这里返回 ROLLBACK_MESSAGE，消息会被丢弃
                // 但延迟消息会在30秒后检查订单状态，如果订单存在则补偿，不存在则回滚
                // 如果发送延迟消息失败，依赖定时任务 InventoryCompensateJob 兜底处理
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        } catch (Exception e) {
            log.error("热点商品-RocketMQ 本地事务执行失败: orderId={}", 
                    aggregate != null ? aggregate.getOrderId() : "unknown", e);
            
            // 外层异常，直接回滚
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
        String orderId = "unknown";
        try {
            // 1. 解析消息（参考 NFTurbo）
            MessageBody messageBody = JSON.parseObject(new String(messageExt.getBody()), MessageBody.class);
            HotGoodsOrderAggregate aggregate = JSON.parseObject(messageBody.getBody(), HotGoodsOrderAggregate.class);

            orderId = aggregate.getOrderId();
            String userId = aggregate.getUserEntity().getUserId();

            // 参考 NFTurbo：直接查询订单状态
            MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
            
            if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                // 订单已创建成功，说明本地事务已成功
                log.info("热点商品-事务回查-订单已创建成功: orderId={}, status={}", orderId, order.getTradeOrderStatusEnumVO());
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
                log.warn("热点商品-事务回查-订单不存在且Redis流水不存在，本地事务失败: orderId={}", orderId);
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }

            // Redis 流水存在但订单不存在，可能是订单创建失败
            log.warn("热点商品-事务回查-Redis流水存在但订单不存在，可能订单创建失败: orderId={}", orderId);
            return LocalTransactionState.ROLLBACK_MESSAGE;

        } catch (Exception e) {
            log.error("热点商品-事务回查异常，返回ROLLBACK: orderId={}, error={}", orderId, e.getMessage(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }


    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

