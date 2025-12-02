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
        try {
            Message<String> rocketMessage = MessageBuilder.withPayload(message)
                    .setHeader(RocketMQHeaders.TAGS, tag)
                    .setHeader("delayLevel", delayLevel) // RocketMQ 延迟消息通过 header 设置
                    .build();
            // RocketMQ 延迟消息：delayLevel 1-18 对应 1s, 5s, 10s, 30s, 1m, 2m, 3m, 4m, 5m, 6m, 7m, 8m, 9m, 10m, 20m, 30m, 1h, 2h
            org.apache.rocketmq.client.producer.SendResult result = rocketMQTemplate.syncSend(
                    topic + ":" + tag, 
                    rocketMessage
            );
            boolean success = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            log.info("发送RocketMQ延迟消息: topic={}, tag={}, delayLevel={}, result={}", topic, tag, delayLevel, success);
            return success;
        } catch (Exception e) {
            log.error("发送RocketMQ延迟消息失败: topic={}, tag={}, delayLevel={}", topic, tag, delayLevel, e);
            return false;
        }
    }

    @Override
    public boolean sendMessage(String topic, String tag, String message) {
        try {
            Message<String> rocketMessage = MessageBuilder.withPayload(message)
                    .setHeader(RocketMQHeaders.TAGS, tag)
                    .build();
            org.apache.rocketmq.client.producer.SendResult result = rocketMQTemplate.syncSend(topic + ":" + tag, rocketMessage);
            boolean success = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            log.info("发送RocketMQ普通消息: topic={}, tag={}, result={}", topic, tag, success);
            return success;
        } catch (Exception e) {
            log.error("发送RocketMQ普通消息失败: topic={}, tag={}", topic, tag, e);
            return false;
        }
    }
}