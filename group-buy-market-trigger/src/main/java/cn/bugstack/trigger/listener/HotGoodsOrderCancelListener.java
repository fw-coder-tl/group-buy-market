package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 热点商品订单取消消息监听器（Trigger层）
 * 
 * 对标 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusCancel
 * 
 * 处理 hotGoodsOrderCancel 消息：
 * 1. 回滚库存（热点商品只回滚商品库存）
 * 2. 取消订单（如果订单存在）
 * 
 * 使用场景：
 * - 当本地事务失败时，发送废单消息，异步处理废单
 * - 保证库存回滚和订单取消的一致性
 * 
 * @author liang.tian
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "hotGoodsOrderCancel", consumerGroup = "hotGoodsOrderCancel-consumer")
public class HotGoodsOrderCancelListener implements RocketMQListener<String> {

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;

    @Override
    public void onMessage(String message) {
        try {
            log.warn("热点商品订单取消消息-收到消息: message={}", message);
            
            // 1. 解析消息
            HotGoodsOrderAggregate aggregate = JSON.parseObject(message, HotGoodsOrderAggregate.class);
            String orderId = aggregate.getOrderId();

            // 2. 执行取消操作
            doCancel(aggregate);

            log.info("热点商品订单取消消息-处理成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("热点商品订单取消消息-处理失败: message={}", message, e);
            throw new RuntimeException("热点商品订单取消消息处理失败", e);
        }
    }

    /**
     * 执行取消操作
     * 
     * 对标 NFTurbo 的 NewBuyPlusMsgListener.doCancel（但简化了，因为热点商品不做拼团）
     * 
     * 注意：本地事务失败时，需要回滚数据库库存和 Redis 库存
     */
    private void doCancel(HotGoodsOrderAggregate aggregate) {
        String orderId = aggregate.getOrderId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

        // 1. 回滚数据库库存（释放冻结库存）
        boolean releaseResult = skuRepository.releaseSkuStock(activityId, goodsId, 1);
        if (!releaseResult) {
            log.warn("热点商品订单取消-数据库库存回滚失败（可能库存未扣减）: orderId={}", orderId);
        } else {
            log.info("热点商品订单取消-数据库库存回滚成功: orderId={}", orderId);
        }

        // 2. 回滚 Redis 库存
        boolean cancelResult = skuRepository.cancelDecreaseInventory(activityId, goodsId, 1, orderId);
        if (!cancelResult) {
            log.warn("热点商品订单取消-Redis库存回滚失败（可能库存未扣减）: orderId={}", orderId);
        } else {
            log.info("热点商品订单取消-Redis库存回滚成功: orderId={}", orderId);
        }

        // 3. 取消订单（如果订单存在，将订单状态改为 CLOSE）
        // ⚠️ 注意：热点商品订单如果创建失败，可能订单不存在，所以这里需要判断
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

