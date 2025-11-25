package cn.bugstack.infrastructure.mq.producer;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消息生产者实现 (Infrastructure层)
 */
@Slf4j
@Component
public class MessageProducerImpl implements IMessageProducer {
    
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    
    @Override
    public boolean sendOrderCreateMessage(String topic, String tag, String message, Object arg) {
        try {
            Message<String> rocketMessage = MessageBuilder.withPayload(message)
                    .setHeader(RocketMQHeaders.TAGS, tag)
                    .build();
            TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(topic, rocketMessage, arg);
            boolean success = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            log.info("发送RocketMQ事务消息: topic={}, tag={}, result={}", topic, tag, success);
            return success;
        } catch (Exception e) {
            log.error("发送RocketMQ事务消息失败: topic={}, tag={}", topic, tag, e);
            return false;
        }
    }
    
    @Override
    public boolean sendDelayMessage(String topic, String tag, String message, int delayLevel) {
        // 暂未使用延迟消息
        log.warn("暂不支持RocketMQ延迟消息发送, topic={}, tag={}, delayLevel={}", topic, tag, delayLevel);
        return false;
    }
}