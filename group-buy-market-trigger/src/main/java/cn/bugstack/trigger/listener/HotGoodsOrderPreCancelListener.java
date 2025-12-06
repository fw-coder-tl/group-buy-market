package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.function.Consumer;

/**
 * 热点商品订单疑似取消消息监听器（Trigger层）
 * 
 * 参考 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusPreCancel
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 重要说明：这个监听器只在异常补偿流程中使用
 * 
 * 【完整链路说明】
 * 
 * 1. 正常流程（订单创建成功）：
 *    - RocketMQ 本地事务（HotGoodsOrderCreateTransactionListener.executeLocalTransaction）：
 *      a. 扣减 Redis 库存（在本地事务中）
 *      b. 创建订单（在本地事务中）
 *      c. 不扣减数据库库存（参考 NFTurbo：setSyncDecreaseInventory(false)）
 *    - 如果成功 → 返回 COMMIT_MESSAGE → 消息发送到 hotGoodsOrderCreate-out-0
 *    - HotGoodsOrderCreateMessageListener 消费消息：
 *      a. 批量消费消息（提高性能）
 *      b. 异步扣减数据库库存（TCC Try阶段：增加冻结库存）
 *      c. 带幂等性检查，如果已扣减过会直接返回成功
 * 
 * 2. 异常补偿流程（订单创建失败）：
 *    - 订单创建失败 → 发送延迟消息到 hotGoodsOrderPreCancel（30秒后检查）
 *    - 这个监听器处理延迟消息：
 *      a. 如果订单已创建成功（状态为 CREATE）→ 说明之前查询失败（网络延迟或数据库异常）
 *         → 做补偿处理：扣减数据库库存（因为本地事务中只扣减了 Redis 库存）
 *      b. 如果订单不存在 → 回滚 Redis 库存
 * 
 * 【设计思路】
 * - 解决 OrderCreateTransactionListener 中因为网络延迟或数据库异常而导致查询到的订单状态不是 CREATE，
 *   但是后来又变成了 CREATE 的情况
 * - 延迟检查（30秒），给订单创建足够的时间
 * - 正常流程的数据库库存扣减在 HotGoodsOrderCreateMessageListener 中异步执行
 * - 异常补偿流程的数据库库存扣减在这个监听器中执行（处理"假失败"情况）
 * 
 * 【对比 NFTurbo】
 * - NFTurbo 的 newBuyPlusPreCancel 监听器逻辑：
 *   - 如果订单存在 → 调用 saleWithoutHint 扣减数据库库存（补偿）
 *   - 如果订单不存在 → 回滚 Redis 库存
 * - 本实现完全对标 NFTurbo 的逻辑
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotGoodsOrderPreCancelListener extends AbstractStreamConsumer {

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;
    
    @Resource
    private IRedisAdapter redisAdapter;
    
    // 商品库存键前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    /**
     * 处理热点商品订单疑似取消消息（延迟消息）
     * 
     * 参考 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusPreCancel
     * 
     * 【触发场景】
     * - 订单创建失败时，本地事务监听器会发送延迟消息（30秒后检查）
     * - 这个监听器在30秒后检查订单状态，决定是补偿还是回滚
     * 
     * 【处理逻辑】
     * 1. 查询订单状态
     * 2. 如果订单已创建成功（状态为 CREATE）：
     *    - 说明之前查询失败（网络延迟或数据库异常），订单实际已创建
     *    - 做补偿处理：扣减数据库库存（因为本地事务中只扣减了 Redis 库存）
     *    - 参考 NFTurbo: goodsFacadeService.saleWithoutHint() - 扣减数据库库存
     * 3. 如果订单不存在：
     *    - 说明订单真的创建失败
     *    - 回滚 Redis 库存（因为 Redis 库存已扣减，但订单未创建）
     *    - 参考 NFTurbo: inventoryFacadeService.increase() - 回滚 Redis 库存
     */
    @Bean
    Consumer<Message<MessageBody>> hotGoodsOrderPreCancel() {
        return msg -> {
            try {
                log.warn("热点商品订单疑似取消消息-收到延迟检查消息（30秒后检查订单状态）");
                
                // 1. 解析消息（参考 NFTurbo）
                HotGoodsOrderAggregate aggregate = getMessage(msg, HotGoodsOrderAggregate.class);
                String orderId = aggregate.getOrderId();
                String userId = aggregate.getUserEntity().getUserId();

                // 2. 查询订单状态（延迟30秒后检查，给订单创建足够的时间）
                MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
                
                // 3. 如果订单已经创建成功（状态为 CREATE），说明之前查询失败（网络延迟或数据库异常）
                // 参考 NFTurbo: if (response.getSuccess() && response.getData() != null && response.getData().getOrderState() == TradeOrderState.CONFIRM)
                if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                    log.info("热点商品订单疑似取消消息-订单已创建成功（假失败），做补偿处理: orderId={}, status={}", 
                            orderId, order.getTradeOrderStatusEnumVO());
                    
                    // 关键：如果订单已创建但之前查询失败，做补偿处理（扣减数据库库存）
                    // 原因：本地事务中只扣减了 Redis 库存，没有扣减数据库库存（setSyncDecreaseInventory=false）
                    // 参考 NFTurbo: goodsFacadeService.saleWithoutHint() - 扣减数据库库存
                    Long activityId = aggregate.getPayActivityEntity().getActivityId();
                    String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
                    
                    // 扣减数据库库存（带幂等性检查，如果已扣减过会直接返回成功）
                    // 参考 NFTurbo: goodsSaleResponse = goodsFacadeService.saleWithoutHint(goodsSaleRequest)
                    boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1, orderId, userId);
                    if (!decreaseResult) {
                        log.warn("热点商品订单疑似取消消息-数据库库存扣减补偿失败（可能已扣减）: orderId={}", orderId);
                    } else {
                        log.info("热点商品订单疑似取消消息-数据库库存扣减补偿成功: orderId={}", orderId);
                    }
                    
                    return;
                }

                // 4. 订单不存在，说明订单真的创建失败，回滚 Redis 库存
                // 参考 NFTurbo: inventoryFacadeService.increase() - 回滚 Redis 库存
                log.warn("热点商品订单疑似取消消息-订单不存在（真失败），回滚 Redis 库存: orderId={}", orderId);
                doCancel(aggregate);

                log.info("热点商品订单疑似取消消息-处理成功: orderId={}", orderId);
            } catch (Exception e) {
                log.error("热点商品订单疑似取消消息-处理失败", e);
                throw new RuntimeException("热点商品订单疑似取消消息处理失败", e);
            }
        };
    }

    /**
     * 执行取消操作（回滚 Redis 库存）
     * 
     * 优化：在回滚成功后删除扣减流水，避免对账任务一直报错
     * 
     * 【为什么删除扣减流水？】
     * - NFTurbo 的做法是回滚时不删除扣减流水，导致对账任务一直报错，直到24小时后流水过期
     * - 我们的优化：在回滚成功后立即删除扣减流水，避免对账任务持续报错
     * - 这样既解决了问题，又保持了对账任务的准确性
     * 
     * 【回滚逻辑】
     * - 使用 increaseStockWithLog 增加 Redis 库存（回滚）
     * - 记录回滚流水，保证幂等性
     * - 回滚成功后删除扣减流水，避免对账任务报错
     */
    private void doCancel(HotGoodsOrderAggregate aggregate) {
        String orderId = aggregate.getOrderId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

        // 构建 Redis Key
        String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
        String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
        
        // 构建回滚标识和原始扣减流水标识
        String rollbackIdentifier = "ROLLBACK_" + orderId;
        String decreaseIdentifier = "DECREASE_" + orderId;
        
        try {
            // 1. 回滚 Redis 库存
            Long rollbackResult = redisAdapter.increaseStockWithLog(goodsStockKey, goodsStockLogKey, rollbackIdentifier, 1);
            if (rollbackResult == null) {
                log.warn("热点商品订单疑似取消-Redis库存回滚失败: orderId={}, activityId={}, goodsId={}", 
                        orderId, activityId, goodsId);
                return;
            }
            
            log.info("热点商品订单疑似取消-Redis库存回滚成功: orderId={}, activityId={}, goodsId={}, 回滚后库存={}", 
                    orderId, activityId, goodsId, rollbackResult);
            
            // 2. 回滚成功后，删除扣减流水（优化：避免对账任务一直报错）
            // 注意：NFTurbo 的做法是不删除扣减流水，导致对账任务一直报错，直到24小时后流水过期
            // 我们的优化：在回滚成功后立即删除扣减流水，避免对账任务持续报错
            try {
                redisAdapter.removeStockDecreaseLog(goodsStockLogKey, decreaseIdentifier);
                log.info("热点商品订单疑似取消-删除扣减流水成功: orderId={}, activityId={}, goodsId={}, identifier={}", 
                        orderId, activityId, goodsId, decreaseIdentifier);
            } catch (Exception e) {
                // 删除失败不影响主流程，记录日志即可（补偿任务会兜底处理）
                log.warn("热点商品订单疑似取消-删除扣减流水失败（不影响主流程，补偿任务会兜底）: orderId={}, activityId={}, goodsId={}, identifier={}, error={}", 
                        orderId, activityId, goodsId, decreaseIdentifier, e.getMessage());
            }
            
        } catch (Exception e) {
            log.error("热点商品订单疑似取消-Redis库存回滚异常: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId, e);
        }
    }
}

