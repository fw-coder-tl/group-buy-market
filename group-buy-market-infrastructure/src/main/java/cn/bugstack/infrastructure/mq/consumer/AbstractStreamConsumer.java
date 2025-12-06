package cn.bugstack.infrastructure.mq.consumer;

import cn.bugstack.infrastructure.mq.param.MessageBody;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;

import static cn.bugstack.infrastructure.mq.producer.StreamProducer.*;

/**
 * MQ消费基类（参考 NFTurbo）
 *
 * @author liang.tian
 */
@Slf4j
public class AbstractStreamConsumer {

    /**
     * 从 msg 中解析出消息对象
     *
     * @param msg  消息
     * @param type 目标类型
     * @param <T>  泛型
     * @return 解析后的对象
     */
    public static <T> T getMessage(Message<MessageBody> msg, Class<T> type) {
        String messageId = msg.getHeaders().get(ROCKET_MQ_MESSAGE_ID, String.class);
        String tag = msg.getHeaders().get(ROCKET_TAGS, String.class);
        String topic = msg.getHeaders().get(ROCKET_MQ_TOPIC, String.class);
        Object object = JSON.parseObject(msg.getPayload().getBody(), type);
        log.info("接收消息: topic={}, messageId={}, tag={}, object={}", topic, messageId, tag, JSON.toJSONString(object));
        return (T) object;
    }
}

