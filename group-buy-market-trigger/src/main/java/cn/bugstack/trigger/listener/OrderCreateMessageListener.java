package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.function.Consumer;

/**
 * 订单创建消息消费者（RocketMQ）
 * 
 * 参考 NFTurbo 的 newBuyPlus 实现
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 改造说明：
 * 由于订单已在本地事务（OrderCreateTransactionListener.executeLocalTransaction）中创建，
 * 消费者主要用于补偿和幂等性处理。
 * 
 * - 订单已在本地事务中创建，消费者主要用于补偿逻辑
 * - 如果订单已存在，直接返回成功（幂等性）
 * - 如果订单不存在，可能是消息延迟或异常，记录日志但不抛异常（避免重试）
 */
@Slf4j
@Component
public class OrderCreateMessageListener extends AbstractStreamConsumer {

    @Resource
    private ITradeRepository tradeRepository;

    @Bean
    Consumer<Message<MessageBody>> orderCreate() {
        return msg -> {
            String orderId = "unknown";
            try {
                log.info("接收到订单创建消息（补偿）");

                // 解析消息（参考 NFTurbo）
                GroupBuyOrderAggregate aggregate = getMessage(msg, GroupBuyOrderAggregate.class);

                String userId = aggregate.getUserEntity().getUserId();
                orderId = aggregate.getOrderId();

                log.info("消费订单创建消息: orderId={}", orderId);

                // ⭐ 改造：由于订单已在本地事务中创建，这里主要用于幂等性检查和补偿
                // 1. 查询订单是否存在（幂等性检查）
                MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
                
                if (order != null) {
                    // 订单已存在，说明本地事务已成功，直接返回（幂等性）
                    log.info("订单已存在（幂等），跳过处理: orderId={}, status={}", 
                            orderId, order.getTradeOrderStatusEnumVO());
                    return;
                }

                // 2. 订单不存在，可能是以下情况：
                //    a. 本地事务失败，订单未创建（正常情况，无需处理）
                //    b. 消息延迟，订单还未创建（很少见，因为本地事务是同步的）
                //    c. 数据库异常，订单创建失败（已回滚，无需处理）
                
                // ⭐ 参考 NFTurbo：订单不存在时不抛异常，避免 RocketMQ 重试
                // 因为订单应该在本地事务中创建，如果不存在说明事务失败，无需重试
                log.warn("订单不存在（可能本地事务失败）: orderId={}, 跳过处理，避免重试", orderId);

            } catch (Exception e) {
                log.error("消费订单创建消息失败: orderId={}", orderId, e);
                
                // ⭐ 改造：不抛异常，避免 RocketMQ 重试
                // 因为订单应该在本地事务中创建，如果消费失败，说明可能是消息异常或订单已不存在
                // 抛异常会导致 RocketMQ 重试，但重试也无法创建订单（因为订单创建在本地事务中）
                log.warn("消费订单创建消息异常，但不抛异常避免重试: {}", e.getMessage());
            }
        };
    }
}
