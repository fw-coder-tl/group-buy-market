package cn.bugstack.infrastructure.redis;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Map;

/**
 * Redis适配器实现 (Infrastructure层)
 */
@Slf4j
@Component
public class RedisAdapterImpl implements IRedisAdapter {
    
    @Resource
    private RedissonClient redissonClient;
    
    @Override
    public Long decreaseStockWithLog(String stockKey, String logKey, String identifier, int count) {
        String luaScript = String.join("\n",
                "-- 幂等性检查",
                "if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then",
                "    return redis.error_reply('OPERATION_ALREADY_EXECUTED')",
                "end",
                "",
                "-- 库存检查",
                "local current = redis.call('get', KEYS[1])",
                "if current == false then",
                "    return redis.error_reply('STOCK_KEY_NOT_FOUND')",
                "end",
                "if tonumber(current) < tonumber(ARGV[1]) then",
                "    return redis.error_reply('STOCK_NOT_ENOUGH')",
                "end",
                "",
                "-- 原子扣减",
                "local new = tonumber(current) - tonumber(ARGV[1])",
                "redis.call('set', KEYS[1], tostring(new))",
                "",
                "-- 记录流水 (用于旁路验证)",
                "local time = redis.call('time')",
                "local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
                "redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",
                "    action = 'decrease',",
                "    from = current,",
                "    to = new,",
                "    change = ARGV[1],",
                "    by = ARGV[2],",
                "    timestamp = timestamp",
                "}))",
                "",
                "return new"
        );
        
        try {
            return redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.INTEGER,
                Arrays.asList(stockKey, logKey),
                count, identifier
            );
        } catch (RedisException e) {
            log.error("Redis扣减库存失败: {}", e.getMessage());
            return -1L;
        }
    }
    
    @Override
    public Long increaseStockWithLog(String stockKey, String logKey, String identifier, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 幂等性检查\n");
        sb.append("if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then\n");
        sb.append("    return redis.error_reply('OPERATION_ALREADY_EXECUTED')\n");
        sb.append("end\n\n");

        sb.append("local current = redis.call('get', KEYS[1])\n");
        sb.append("if current == false then\n");
        sb.append("    current = '0'\n");
        sb.append("end\n\n");

        sb.append("local new = tonumber(current) + tonumber(ARGV[1])\n");
        sb.append("redis.call('set', KEYS[1], tostring(new))\n\n");

        sb.append("-- 记录回滚流水\n");
        sb.append("local time = redis.call('time')\n");
        sb.append("local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)\n");
        sb.append("redis.call('hset', KEYS[2], ARGV[2], cjson.encode({\n");
        sb.append("    action = 'increase',\n");
        sb.append("    from = current,\n");
        sb.append("    to = new,\n");
        sb.append("    change = ARGV[1],\n");
        sb.append("    by = ARGV[2],\n");
        sb.append("    timestamp = timestamp\n");
        sb.append("}))\n\n");

        sb.append("return new\n");

        String luaScript = sb.toString();

        return redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.INTEGER,
                Arrays.asList(stockKey, logKey),
                count, identifier
        );
    }
    
    @Override
    public String getStockDecreaseLog(String logKey, String identifier) {
        return redissonClient.getScript().eval(
            RScript.Mode.READ_ONLY,
            "return redis.call('hget', KEYS[1], ARGV[1])",
            RScript.ReturnType.STATUS,
            Arrays.asList(logKey),
            identifier
        );
    }
    
    @Override
    public void removeStockDecreaseLog(String logKey, String identifier) {
        redissonClient.getScript().eval(
            RScript.Mode.READ_WRITE,
            "return redis.call('hdel', KEYS[1], ARGV[1])",
            RScript.ReturnType.INTEGER,
            Arrays.asList(logKey),
            identifier
        );
    }

    @Override
    public Iterable<String> scanStockLogKeys(String pattern) {
        return redissonClient.getKeys().getKeysByPattern(pattern);
    }

    @Override
    public Map<String, String> getAllStockDecreaseLogs(String logKey) {
        RMap<String, String> map = redissonClient.getMap(logKey);
        return map.readAllMap();
    }

    @Override
    public boolean initTeamStock(String teamId, Integer stockCount) {
        String stockKey = "group_buy_market_team_stock_key_" + teamId;
        
        try {
            // 参考NFTurbo实现：先检查是否存在，避免重复初始化
            if (redissonClient.getBucket(stockKey).isExists()) {
                log.debug("团队库存已存在，跳过初始化: teamId={}, stockKey={}", teamId, stockKey);
                return false; // 已存在，返回false表示未执行初始化
            }
            
            // 直接设置库存值（类似NFTurbo的简单set操作）
            redissonClient.getBucket(stockKey).set(stockCount);
            log.info("初始化团队库存成功: teamId={}, stockCount={}, stockKey={}", teamId, stockCount, stockKey);
            return true;
        } catch (RedisException e) {
            log.error("初始化团队库存失败: teamId={}, stockCount={}, error={}", teamId, stockCount, e.getMessage());
            return false;
        }
    }

    @Override
    public int batchInitTeamStock(Map<String, Integer> teamStockMap) {
        if (teamStockMap == null || teamStockMap.isEmpty()) {
            return 0;
        }
        
        int successCount = 0;
        for (Map.Entry<String, Integer> entry : teamStockMap.entrySet()) {
            if (initTeamStock(entry.getKey(), entry.getValue())) {
                successCount++;
            }
        }
        
        log.info("批量初始化团队库存完成: 总数={}, 成功={}", teamStockMap.size(), successCount);
        return successCount;
    }
}