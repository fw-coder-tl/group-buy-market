package cn.bugstack.domain.trade.constant;

/**
 * 消息延迟级别常量（Domain层）
 * 
 * RocketMQ 支持的延迟级别：
 * 1=1s, 2=5s, 3=10s, 4=30s, 5=1m, 6=2m, 7=3m, 8=4m, 9=5m, 10=6m,
 * 11=7m, 12=8m, 13=9m, 14=10m, 15=20m, 16=30m, 17=1h, 18=2h
 * 
 * @author liang.tian
 */
public class MessageDelayLevel {
    
    /**
     * 延迟30秒（RocketMQ delayLevel 4）
     * 用于热点商品订单疑似废单的延迟检查
     */
    public static final int DELAY_30_SECONDS = 4;
    
    /**
     * 延迟1分钟（RocketMQ delayLevel 5）
     * 用于普通商品订单疑似废单的延迟检查
     */
    public static final int DELAY_1_MINUTE = 5;
    
    /**
     * 延迟3秒（RocketMQ delayLevel 1）
     * 用于旁路验证等场景
     */
    public static final int DELAY_3_SECONDS = 1;
    
    private MessageDelayLevel() {
        // 工具类，禁止实例化
    }
}

