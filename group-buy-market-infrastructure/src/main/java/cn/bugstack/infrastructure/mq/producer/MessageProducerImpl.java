package cn.bugstack.infrastructure.mq.producer;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 消息生产者实现 (Infrastructure层)
 * 参考 NFTurbo 实现，使用 StreamProducer
 */
@Slf4j
@Component
public class MessageProducerImpl implements IMessageProducer {
    
    @Resource
    private StreamProducer streamProducer;
    
    @Override
    public boolean sendOrderCreateMessage(String topic, String tag, String message, Object arg) {
        try {
            // 使用 StreamProducer 发送事务消息
            // topic 作为 bindingName（如：hotGoodsOrderCreate-out-0）
            // arg 参数会通过事务监听器获取，这里只需要发送消息
            boolean success = streamProducer.send(topic, tag, message);
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
            // 使用 StreamProducer 发送延迟消息
            boolean success = streamProducer.send(topic, tag, message, delayLevel);
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
            // 使用 StreamProducer 发送普通消息
            boolean success = streamProducer.send(topic, tag, message);
            log.info("发送RocketMQ普通消息: topic={}, tag={}, result={}", topic, tag, success);
            return success;
        } catch (Exception e) {
            log.error("发送RocketMQ普通消息失败: topic={}, tag={}", topic, tag, e);
            return false;
        }
    }
}