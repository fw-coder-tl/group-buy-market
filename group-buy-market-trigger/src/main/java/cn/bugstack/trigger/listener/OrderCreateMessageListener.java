package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 订单创建消息消费者（RocketMQ）
 */
/**
 * 订单创建消息消费者（RocketMQ）
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "orderCreate-out-0",  // 消费的 topic，对应生产端发送的 topic
        consumerGroup = "group-buy-market-order-create-consumer",  // 消费者组
        selectorExpression = "*"  // 标签过滤，* 表示接收所有标签
)
public class OrderCreateMessageListener implements RocketMQListener<String> {

    @Resource
    private ITradeRepository tradeRepository;

    @Resource
    private ISkuRepository skuRepository;

    @Override
    public void onMessage(String message) {
        try {
            log.info("接收到订单创建消息: {}", message);

            GroupBuyOrderAggregate aggregate = JSON.parseObject(message, GroupBuyOrderAggregate.class);

            Long activityId = aggregate.getPayActivityEntity().getActivityId();
            String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
            String orderId = aggregate.getOrderId();

            log.info("消费订单创建消息: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);

            // 1. 扣减数据库SKU库存（乐观锁）
            boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1);
            if (!decreaseResult) {
                log.error("数据库SKU库存扣减失败: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
                throw new RuntimeException("数据库库存扣减失败");
            }

            // 2. 创建订单记录
            MarketPayOrderEntity orderEntity = tradeRepository.lockMarketPayOrder(aggregate);

            log.info("订单创建成功: orderId={}, teamId={}", orderId, orderEntity.getTeamId());

        } catch (Exception e) {
            log.error("消费订单创建消息失败", e);
            // 抛出异常会触发 RocketMQ 的重试机制
            throw new RuntimeException("订单创建失败", e);
        }
    }
}