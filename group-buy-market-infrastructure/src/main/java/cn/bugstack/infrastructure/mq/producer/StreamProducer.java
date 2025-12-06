package cn.bugstack.infrastructure.mq.producer;

import cn.bugstack.infrastructure.mq.param.MessageBody;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageConst;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stream 消息生产者（参考 NFTurbo）
 *
 * @author liang.tian
 */
@Slf4j
@Component
public class StreamProducer {

    public static final int DELAY_LEVEL_1_M = 5;
    public static final int DELAY_LEVEL_30_S = 4;
    public static final String ROCKET_MQ_MESSAGE_ID = "ROCKET_MQ_MESSAGE_ID";
    public static final String ROCKET_TAGS = "ROCKET_TAGS";
    public static final String ROCKET_MQ_TOPIC = "ROCKET_MQ_TOPIC";

    @Autowired
    private StreamBridge streamBridge;

    /**
     * 发送消息
     *
     * @param bindingName binding 名称（如：hotGoodsOrderCreate-out-0）
     * @param tag         消息标签
     * @param msg         消息内容（JSON 字符串）
     * @return 是否成功
     */
    public boolean send(String bindingName, String tag, String msg) {
        // 构建消息对象
        MessageBody message = new MessageBody()
                .setIdentifier(UUID.randomUUID().toString())
                .setBody(msg);
        log.info("发送消息: bindingName={}, tag={}, message={}", bindingName, tag, JSON.toJSONString(message));
        boolean result = streamBridge.send(bindingName, MessageBuilder.withPayload(message)
                .setHeader("TAGS", tag)
                .build());
        log.info("发送消息结果: bindingName={}, tag={}, result={}", bindingName, tag, result);
        return result;
    }

    /**
     * 发送延迟消息
     *
     * @param bindingName binding 名称
     * @param tag         消息标签
     * @param msg         消息内容（JSON 字符串）
     * @param delayLevel  RocketMQ支持18个级别的延迟时间，分别为1s、5s、10s、30s、1m、2m、3m、4m、5m、6m、7m、8m、9m、10m、20m、30m、1h、2h
     * @return 是否成功
     */
    public boolean send(String bindingName, String tag, String msg, int delayLevel) {
        // 构建消息对象
        MessageBody message = new MessageBody()
                .setIdentifier(UUID.randomUUID().toString())
                .setBody(msg);
        log.info("发送延迟消息: bindingName={}, tag={}, delayLevel={}, message={}", bindingName, tag, delayLevel, JSON.toJSONString(message));
        boolean result = streamBridge.send(bindingName, MessageBuilder.withPayload(message)
                .setHeader("TAGS", tag)
                .setHeader(MessageConst.PROPERTY_DELAY_TIME_LEVEL, delayLevel)
                .build());
        log.info("发送延迟消息结果: bindingName={}, tag={}, delayLevel={}, result={}", bindingName, tag, delayLevel, result);
        return result;
    }

    /**
     * 发送带自定义 header 的消息
     *
     * @param bindingName binding 名称
     * @param tag         消息标签
     * @param msg         消息内容（JSON 字符串）
     * @param headerKey   header key
     * @param headerValue header value
     * @return 是否成功
     */
    public boolean send(String bindingName, String tag, String msg, String headerKey, String headerValue) {
        // 构建消息对象
        MessageBody message = new MessageBody()
                .setIdentifier(UUID.randomUUID().toString())
                .setBody(msg);
        log.info("发送消息（带自定义header）: bindingName={}, tag={}, headerKey={}, headerValue={}, message={}", 
                bindingName, tag, headerKey, headerValue, JSON.toJSONString(message));
        boolean result = streamBridge.send(bindingName, MessageBuilder.withPayload(message)
                .setHeader("TAGS", tag)
                .setHeader(headerKey, headerValue)
                .build());
        log.info("发送消息结果（带自定义header）: bindingName={}, tag={}, result={}", bindingName, tag, result);
        return result;
    }
}

