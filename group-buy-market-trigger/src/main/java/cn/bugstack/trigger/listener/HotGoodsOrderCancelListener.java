package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.function.Consumer;

/**
 * 热点商品订单取消消息监听器（Trigger层）
 * 
 * 参考 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusCancel
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 处理 hotGoodsOrderCancel 消息：
 * 1. 回滚 Redis 库存
 * 2. 取消订单（如果订单存在）
 * 
 * 使用场景：
 * - 当订单已创建但责任链校验失败时（活动无效、用户参与次数超限等），发送取消消息
 * - 保证库存回滚和订单取消的一致性
 * 
 * 注意：
 * - 只回滚 Redis 库存，不释放数据库冻结库存
 * - 因为在这个场景下，数据库库存可能还没有扣减（数据库库存扣减在消息监听器中补偿）
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotGoodsOrderCancelListener extends AbstractStreamConsumer {

    @Resource
    private ITradeRepository tradeRepository;
    
    @Resource
    private IRedisAdapter redisAdapter;
    
    // 商品库存键前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    @Bean
    Consumer<Message<MessageBody>> hotGoodsOrderCancel() {
        return msg -> {
            try {
                log.warn("热点商品订单取消消息-收到消息");
                
                // 1. 解析消息（参考 NFTurbo）
                HotGoodsOrderAggregate aggregate = getMessage(msg, HotGoodsOrderAggregate.class);
                String orderId = aggregate.getOrderId();

                // 2. 执行取消操作
                doCancel(aggregate);

                log.info("热点商品订单取消消息-处理成功: orderId={}", orderId);
            } catch (Exception e) {
                log.error("热点商品订单取消消息-处理失败", e);
                throw new RuntimeException("热点商品订单取消消息处理失败", e);
            }
        };
    }

    /**
     * 执行取消操作
     * 
     * 对标 NFTurbo 的 NewBuyPlusMsgListener.doCancel（但简化了，因为热点商品不做拼团）
     * 参考 NFTurbo: inventoryTransactionFacadeService.cancelDecrease() + orderTransactionFacadeService.cancelOrder()
     * 
     * 注意：
     * - 只回滚 Redis 库存，不释放数据库冻结库存
     * - 因为在这个场景下（责任链校验失败），数据库库存可能还没有扣减
     * - 数据库库存扣减在消息监听器中补偿，如果校验失败，说明补偿还没执行
     */
    private void doCancel(HotGoodsOrderAggregate aggregate) {
        String orderId = aggregate.getOrderId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

        // 1. 回滚 Redis 库存（参考 NFTurbo: inventoryFacadeService.increase()）
        // 注意：不释放数据库冻结库存，因为数据库库存可能还没有扣减
        String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
        String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
        String identifier = "ROLLBACK_" + orderId;
        
        try {
            Long rollbackResult = redisAdapter.increaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, 1);
            if (rollbackResult == null) {
                log.warn("热点商品订单取消-Redis库存回滚失败: orderId={}, activityId={}, goodsId={}", 
                        orderId, activityId, goodsId);
            } else {
                log.info("热点商品订单取消-Redis库存回滚成功: orderId={}, activityId={}, goodsId={}, 回滚后库存={}", 
                        orderId, activityId, goodsId, rollbackResult);
            }
        } catch (Exception e) {
            log.error("热点商品订单取消-Redis库存回滚异常: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId, e);
        }

        // 2. 取消订单（如果订单存在，将订单状态改为 CLOSE）
        // 参考 NFTurbo: orderTransactionFacadeService.cancelOrder()
        try {
            boolean cancelOrderResult = tradeRepository.cancelOrder(orderId);
            if (cancelOrderResult) {
                log.info("热点商品订单取消-订单取消成功: orderId={}", orderId);
            } else {
                log.warn("热点商品订单取消-订单不存在或已取消: orderId={}", orderId);
            }
        } catch (Exception e) {
            log.warn("热点商品订单取消-订单取消失败（可能订单不存在）: orderId={}", orderId, e);
            // 订单不存在不影响库存回滚，继续执行
        }

        log.info("热点商品订单取消-成功: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
    }
}

