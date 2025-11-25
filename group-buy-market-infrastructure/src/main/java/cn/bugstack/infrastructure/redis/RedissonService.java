package cn.bugstack.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Redis 服务 - Redisson
 */
@Service("redissonService")
@Slf4j
public class RedissonService implements IRedisService {

    @Resource
    private RedissonClient redissonClient;

    public <T> void setValue(String key, T value) {
        redissonClient.<T>getBucket(key).set(value);
    }

    @Override
    public <T> void setValue(String key, T value, long expired) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, Duration.ofMillis(expired));
    }

    public <T> T getValue(String key) {
        return redissonClient.<T>getBucket(key).get();
    }

    @Override
    public <T> RQueue<T> getQueue(String key) {
        return redissonClient.getQueue(key);
    }

    @Override
    public <T> RBlockingQueue<T> getBlockingQueue(String key) {
        return redissonClient.getBlockingQueue(key);
    }

    @Override
    public <T> RDelayedQueue<T> getDelayedQueue(RBlockingQueue<T> rBlockingQueue) {
        return redissonClient.getDelayedQueue(rBlockingQueue);
    }

    @Override
    public void setAtomicLong(String key, long value) {
        redissonClient.getAtomicLong(key).set(value);
    }

    @Override
    public Long getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key).get();
    }

    @Override
    public long incr(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    @Override
    public long incrBy(String key, long delta) {
        return redissonClient.getAtomicLong(key).addAndGet(delta);
    }

    @Override
    public long decr(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    @Override
    public long decrBy(String key, long delta) {
        return redissonClient.getAtomicLong(key).addAndGet(-delta);
    }

    @Override
    public void remove(String key) {
        redissonClient.getBucket(key).delete();
    }

    @Override
    public boolean isExists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    public void addToSet(String key, String value) {
        RSet<String> set = redissonClient.getSet(key);
        set.add(value);
    }

    public boolean isSetMember(String key, String value) {
        RSet<String> set = redissonClient.getSet(key);
        return set.contains(value);
    }

    public void addToList(String key, String value) {
        RList<String> list = redissonClient.getList(key);
        list.add(value);
    }

    public String getFromList(String key, int index) {
        RList<String> list = redissonClient.getList(key);
        return list.get(index);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String key) {
        return redissonClient.getMap(key);
    }

    public void addToMap(String key, String field, String value) {
        RMap<String, String> map = redissonClient.getMap(key);
        map.put(field, value);
    }

    public String getFromMap(String key, String field) {
        RMap<String, String> map = redissonClient.getMap(key);
        return map.get(field);
    }

    @Override
    public <K, V> V getFromMap(String key, K field) {
        return redissonClient.<K, V>getMap(key).get(field);
    }

    public void addToSortedSet(String key, String value) {
        RSortedSet<String> sortedSet = redissonClient.getSortedSet(key);
        sortedSet.add(value);
    }

    @Override
    public RLock getLock(String key) {
        return redissonClient.getLock(key);
    }

    @Override
    public RLock getFairLock(String key) {
        return redissonClient.getFairLock(key);
    }

    @Override
    public RReadWriteLock getReadWriteLock(String key) {
        return redissonClient.getReadWriteLock(key);
    }

    @Override
    public RSemaphore getSemaphore(String key) {
        return redissonClient.getSemaphore(key);
    }

    @Override
    public RPermitExpirableSemaphore getPermitExpirableSemaphore(String key) {
        return redissonClient.getPermitExpirableSemaphore(key);
    }

    @Override
    public RCountDownLatch getCountDownLatch(String key) {
        return redissonClient.getCountDownLatch(key);
    }

    @Override
    public <T> RBloomFilter<T> getBloomFilter(String key) {
        return redissonClient.getBloomFilter(key);
    }

    @Override
    public Boolean setNx(String key) {
        return redissonClient.getBucket(key).trySet("lock");
    }

    @Override
    public Boolean setNx(String key, long expired, TimeUnit timeUnit) {
        return redissonClient.getBucket(key).trySet("lock", expired, timeUnit);
    }

    @Override
    public RBitSet getBitSet(String key) {
        return redissonClient.getBitSet(key);
    }

//    /**
//     * 原子扣减库存并记录流水 (防超卖 + 可追溯)
//     *
//     * @param stockKey 库存Key (如: group_buy_market_team_stock_key_{teamId})
//     * @param logKey 流水Key (如: group_buy_market_team_stock_log_{teamId})
//     * @param identifier 操作标识 (如: DECREASE_{orderId})
//     * @param count 扣减数量
//     * @return 扣减后的库存值,-1表示失败
//     */
//    @Override
//    public Long decreaseStockWithLog(String stockKey, String logKey, String identifier, int count) {
//        String luaScript = String.join("\n",
//                "-- 幂等性检查",
//                "if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then",
//                "    return redis.error_reply('OPERATION_ALREADY_EXECUTED')",
//                "end",
//                "",
//                "-- 库存检查",
//                "local current = redis.call('get', KEYS[1])",
//                "if current == false then",
//                "    return redis.error_reply('STOCK_KEY_NOT_FOUND')",
//                "end",
//                "if tonumber(current) < tonumber(ARGV[1]) then",
//                "    return redis.error_reply('STOCK_NOT_ENOUGH')",
//                "end",
//                "",
//                "-- 原子扣减",
//                "local new = tonumber(current) - tonumber(ARGV[1])",
//                "redis.call('set', KEYS[1], tostring(new))",
//                "",
//                "-- 记录流水 (用于旁路验证)",
//                "local time = redis.call('time')",
//                "local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
//                "redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",
//                "    action = 'decrease',",
//                "    from = current,",
//                "    to = new,",
//                "    change = ARGV[1],",
//                "    by = ARGV[2],",
//                "    timestamp = timestamp",
//                "}))",
//                "",
//                "return new"
//        );
//
//        try {
//            return redissonClient.getScript().eval(
//                    RScript.Mode.READ_WRITE,
//                    luaScript,
//                    RScript.ReturnType.INTEGER,
//                    Arrays.asList(stockKey, logKey),
//                    count, identifier
//            );
//        } catch (RedisException e) {
//            log.error("Redis扣减库存失败: stockKey={}, identifier={}", stockKey, identifier, e);
//            return -1L;
//        }
//    }
//
//    /**
//     * 获取库存扣减流水
//     */
//    @Override
//    public String getStockDecreaseLog(String logKey, String identifier) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("local jsonString = redis.call('hget', KEYS[1], ARGV[1])\n");
//        sb.append("return jsonString\n");
//
//        String luaScript = sb.toString();
//
//        return redissonClient.getScript().eval(
//                RScript.Mode.READ_ONLY,
//                luaScript,
//                RScript.ReturnType.STATUS,
//                Arrays.asList(logKey),
//                identifier
//        );
//    }
//
//    /**
//     * 删除库存扣减流水 (验证成功后清理)
//     */
//    @Override
//    public void removeStockDecreaseLog(String logKey, String identifier) {
//        redissonClient.getScript().eval(
//                RScript.Mode.READ_WRITE,
//                "return redis.call('hdel', KEYS[1], ARGV[1])",
//                RScript.ReturnType.INTEGER,
//                Arrays.asList(logKey),
//                identifier
//        );
//    }
//
//    /**
//     * 回滚库存 (订单失败补偿)
//     */
//    @Override
//    public Long increaseStockWithLog(String stockKey, String logKey, String identifier, int count) {
//        StringBuilder sb = new StringBuilder();
//        sb.append("-- 幂等性检查\n");
//        sb.append("if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then\n");
//        sb.append("    return redis.error_reply('OPERATION_ALREADY_EXECUTED')\n");
//        sb.append("end\n\n");
//
//        sb.append("local current = redis.call('get', KEYS[1])\n");
//        sb.append("if current == false then\n");
//        sb.append("    current = '0'\n");
//        sb.append("end\n\n");
//
//        sb.append("local new = tonumber(current) + tonumber(ARGV[1])\n");
//        sb.append("redis.call('set', KEYS[1], tostring(new))\n\n");
//
//        sb.append("-- 记录回滚流水\n");
//        sb.append("local time = redis.call('time')\n");
//        sb.append("local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)\n");
//        sb.append("redis.call('hset', KEYS[2], ARGV[2], cjson.encode({\n");
//        sb.append("    action = 'increase',\n");
//        sb.append("    from = current,\n");
//        sb.append("    to = new,\n");
//        sb.append("    change = ARGV[1],\n");
//        sb.append("    by = ARGV[2],\n");
//        sb.append("    timestamp = timestamp\n");
//        sb.append("}))\n\n");
//
//        sb.append("return new\n");
//
//        String luaScript = sb.toString();
//
//        return redissonClient.getScript().eval(
//                RScript.Mode.READ_WRITE,
//                luaScript,
//                RScript.ReturnType.INTEGER,
//                Arrays.asList(stockKey, logKey),
//                count, identifier
//        );
//    }


}
