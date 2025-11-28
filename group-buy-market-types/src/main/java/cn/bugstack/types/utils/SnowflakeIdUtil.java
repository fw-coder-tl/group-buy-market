package cn.bugstack.types.utils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author liang.tian
 * @description 雪花算法ID生成工具类
 * @create 2025-01-11
 */
public class SnowflakeIdUtil {

    /**
     * 起始的时间戳 (2024-01-01 00:00:00)
     */
    private static final long START_TIMESTAMP = 1704067200000L;

    /**
     * 序列号占用的位数
     */
    private static final long SEQUENCE_BIT = 12;

    /**
     * 机器标识占用的位数
     */
    private static final long MACHINE_BIT = 5;

    /**
     * 数据中心占用的位数
     */
    private static final long DATACENTER_BIT = 5;

    /**
     * 最大值
     */
    private static final long MAX_DATACENTER_NUM = ~(-1L << DATACENTER_BIT);
    private static final long MAX_MACHINE_NUM = ~(-1L << MACHINE_BIT);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BIT);

    /**
     * 机器ID向左移12位
     */
    private static final long MACHINE_LEFT = SEQUENCE_BIT;

    /**
     * 数据中心ID向左移17位(12+5)
     */
    private static final long DATACENTER_LEFT = SEQUENCE_BIT + MACHINE_BIT;

    /**
     * 时间戳向左移22位(12+5+5)
     */
    private static final long TIMESTAMP_LEFT = DATACENTER_LEFT + MACHINE_BIT;

    /**
     * 数据中心ID(0~31)
     */
    private static final long DATACENTER_ID = 1L;

    /**
     * 机器ID(0~31)
     */
    private static final long MACHINE_ID = 1L;

    /**
     * 序列号
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);

    /**
     * 上一次时间戳
     */
    private static volatile long LAST_TIMESTAMP = -1L;

    /**
     * 产生下一个ID
     *
     * @return 雪花算法生成的ID
     */
    public static synchronized long nextId() {
        long currTimestamp = getNewTimestamp();
        if (currTimestamp < LAST_TIMESTAMP) {
            throw new RuntimeException("时钟向后移动，拒绝生成ID");
        }

        if (currTimestamp == LAST_TIMESTAMP) {
            // 相同毫秒内，序列号自增
            long sequence = SEQUENCE.incrementAndGet() & MAX_SEQUENCE;
            // 同一毫秒的序列数已经达到最大
            if (sequence == 0L) {
                currTimestamp = getNextMill();
            }
        } else {
            // 不同毫秒内，序列号置为0
            SEQUENCE.set(0L);
        }

        LAST_TIMESTAMP = currTimestamp;

        return (currTimestamp - START_TIMESTAMP) << TIMESTAMP_LEFT
                | DATACENTER_ID << DATACENTER_LEFT
                | MACHINE_ID << MACHINE_LEFT
                | SEQUENCE.get();
    }

    /**
     * 生成字符串格式的ID
     *
     * @return 字符串格式的雪花算法ID
     */
    public static String nextIdStr() {
        return String.valueOf(nextId());
    }

    /**
     * 阻塞到下一个毫秒，直到获得新的时间戳
     *
     * @return 新的时间戳
     */
    private static long getNextMill() {
        long mill = getNewTimestamp();
        while (mill <= LAST_TIMESTAMP) {
            mill = getNewTimestamp();
        }
        return mill;
    }

    /**
     * 返回以毫秒为单位的当前时间
     *
     * @return 当前时间(毫秒)
     */
    private static long getNewTimestamp() {
        return System.currentTimeMillis();
    }
}

