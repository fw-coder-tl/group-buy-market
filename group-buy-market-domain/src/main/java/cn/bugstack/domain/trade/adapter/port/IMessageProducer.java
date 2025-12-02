package cn.bugstack.domain.trade.adapter.port;

/**
 * 消息生产者接口 (Domain层定义,Infrastructure层实现)
 */
public interface IMessageProducer {
    
    /**
     * 发送订单创建事务消息
     *
     * @param topic   主题
     * @param tag     标签
     * @param message 消息体
     * @param arg     本地事务参数
     * @return 是否成功
     */
    boolean sendOrderCreateMessage(String topic, String tag, String message, Object arg);

    /**
     * 发送延迟消息
     */
    boolean sendDelayMessage(String topic, String tag, String message, int delayLevel);

    /**
     * 发送普通消息（非事务消息）
     * @param topic 主题
     * @param tag 标签
     * @param message 消息体
     * @return 是否成功
     */
    boolean sendMessage(String topic, String tag, String message);
}