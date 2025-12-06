package cn.bugstack.infrastructure.redis;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
    public Long decreaseTeamStockDynamically(String stockKey, String logKey, String identifier, int targetCount) {
        String luaScript = String.join("\n",
                "-- 幂等性检查：防止同一订单重复扣减",
                "if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then",
                "    return -2  -- 重复操作",
                "end",
                "",
                "-- 获取当前队伍库存",
                "local current = redis.call('get', KEYS[1])",
                "",
                "-- 如果队伍库存不存在，初始化为 1（首次加入队伍）",
                "if current == false then",
                "    redis.call('set', KEYS[1], '1')",
                "    ",
                "    -- 记录扣减流水",
                "    local time = redis.call('time')",
                "    local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
                "    redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",
                "        action = 'decrease_team',",
                "        from = 0,",
                "        to = 1,",
                "        change = 1,",
                "        targetCount = tonumber(ARGV[1]),",
                "        by = ARGV[2],",
                "        timestamp = timestamp",
                "    }))",
                "    -- 设置流水Hash的过期时间为24小时",
                "    redis.call('expire', KEYS[2], 86400)",
                "    ",
                "    return 1  -- 首次加入，返回 1",
                "end",
                "",
                "-- 检查队伍是否已满",
                "local currentCount = tonumber(current)",
                "local target = tonumber(ARGV[1])",
                "",
                "if currentCount >= target then",
                "    return -1  -- 队伍已满",
                "end",
                "",
                "-- 队伍未满，执行扣减（实际是 +1）",
                "local new = currentCount + 1",
                "redis.call('set', KEYS[1], tostring(new))",
                "",
                "-- 记录扣减流水",
                "local time = redis.call('time')",
                "local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
                "redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",
                "    action = 'decrease_team',",
                "    from = currentCount,",
                "    to = new,",
                "    change = 1,",
                "    targetCount = target,",
                "    by = ARGV[2],",
                "    timestamp = timestamp",
                "}))",
                "-- 设置流水Hash的过期时间为24小时",
                "redis.call('expire', KEYS[2], 86400)",
                "",
                "return new  -- 返回新的队伍人数"
        );

        try {
            Long result = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.INTEGER,
                    Arrays.asList(stockKey, logKey),
                    targetCount, identifier
            );

            log.info("队伍库存动态扣减: stockKey={}, targetCount={}, 当前人数={}",
                    stockKey, targetCount, result);

            return result;
        } catch (RedisException e) {
            log.error("队伍库存动态扣减失败: stockKey={}, error={}", stockKey, e.getMessage());
            return -1L;
        }
    }

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
                "redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",  // 继续使用 cjson.encode
                "    action = 'decrease',",
                "    from = current,",
                "    to = new,",
                "    change = ARGV[1],",
                "    by = ARGV[2],",
                "    timestamp = timestamp",
                "}))",
                "-- 设置流水Hash的过期时间为24小时，避免流水立即过期导致对账问题",
                "redis.call('expire', KEYS[2], 86400)",
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
        String luaScript = String.join("\n",
                "-- 幂等性检查",
                "if redis.call('hexists', KEYS[2], ARGV[2]) == 1 then",
                "    return redis.error_reply('OPERATION_ALREADY_EXECUTED')",
                "end",
                "",
                "local current = redis.call('get', KEYS[1])",
                "if current == false then",
                "    current = '0'",
                "end",
                "",
                "local new = tonumber(current) + tonumber(ARGV[1])",
                "redis.call('set', KEYS[1], tostring(new))",
                "",
                "-- 记录回滚流水",
                "local time = redis.call('time')",
                "local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
                "redis.call('hset', KEYS[2], ARGV[2], cjson.encode({",  // 继续使用 cjson.encode
                "    action = 'increase',",
                "    from = current,",
                "    to = new,",
                "    change = ARGV[1],",
                "    by = ARGV[2],",
                "    timestamp = timestamp",
                "}))",
                "-- 设置流水Hash的过期时间为24小时",
                "redis.call('expire', KEYS[2], 86400)",
                "",
                "return new"
        );

        // 使用 StringCodec 执行 Lua 脚本
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.INTEGER,
                Arrays.asList(stockKey, logKey),
                count, identifier
        );
    }

    @Override
    public String getStockDecreaseLog(String logKey, String identifier) {
        try {
            String luaScript = "return redis.call('hget', KEYS[1], ARGV[1])";

            // 使用 StringCodec 避免序列化问题
            String result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_ONLY,
                    luaScript,
                    RScript.ReturnType.VALUE,
                    Arrays.asList(logKey),
                    identifier
            );

            return result;

        } catch (Exception e) {
            log.error("读取库存扣减日志失败: logKey={}, identifier={}, error={}",
                    logKey, identifier, e.getMessage());
            return null;
        }
    }

    @Override
    public void removeStockDecreaseLog(String logKey, String identifier) {
        try {
            String luaScript = "return redis.call('hdel', KEYS[1], ARGV[1])";

            // 使用 StringCodec
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.INTEGER,
                    Arrays.asList(logKey),
                    identifier
            );

        } catch (Exception e) {
            log.error("删除库存扣减日志失败: logKey={}, identifier={}, error={}",
                    logKey, identifier, e.getMessage());
        }
    }

    @Override
    public Iterable<String> scanStockLogKeys(String pattern) {
        return redissonClient.getKeys().getKeysByPattern(pattern);
    }

    @Override
    public Map<String, String> getAllStockDecreaseLogs(String logKey) {
        try {
            String luaScript = "return redis.call('HGETALL', KEYS[1])";

            // 使用 StringCodec 避免序列化问题
            List<String> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_ONLY,
                    luaScript,
                    RScript.ReturnType.MULTI,
                    Arrays.asList(logKey)
            );

            // 将 List 转换为 Map
            Map<String, String> map = new HashMap<>();
            if (result != null && !result.isEmpty()) {
                for (int i = 0; i < result.size(); i += 2) {
                    String key = result.get(i);
                    String value = (i + 1 < result.size()) ? result.get(i + 1) : null;
                    if (value != null) {
                        map.put(key, value);
                    }
                }
            }

            log.debug("读取库存流水成功: logKey={}, count={}", logKey, map.size());
            return map;

        } catch (Exception e) {
            log.error("读取库存流水失败: logKey={}, error={}", logKey, e.getMessage(), e);
            return new HashMap<>();
        }
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

    @Override
    public boolean initGoodsStock(Long activityId, String goodsId, Integer stockCount) {
        // 使用与 OrderCreateTransactionListener 中相同的键格式
        String stockKey = "group_buy_market_goods_stock_" + activityId + "_" + goodsId;
        
        try {
            // 先检查是否存在，避免重复初始化
            if (redissonClient.getBucket(stockKey).isExists()) {
                log.debug("商品库存已存在，跳过初始化: activityId={}, goodsId={}, stockKey={}", 
                        activityId, goodsId, stockKey);
                return false; // 已存在，返回false表示未执行初始化
            }
            
            // 直接设置库存值
            redissonClient.getBucket(stockKey).set(stockCount);
            log.info("初始化商品库存成功: activityId={}, goodsId={}, stockCount={}, stockKey={}", 
                    activityId, goodsId, stockCount, stockKey);
            return true;
        } catch (RedisException e) {
            log.error("初始化商品库存失败: activityId={}, goodsId={}, stockCount={}, error={}", 
                    activityId, goodsId, stockCount, e.getMessage());
            return false;
        }
    }

    /**
     * 原子操作：扣减商品库存 + 增加队伍人数
     * 
     * 对标 newBuyPlus：在同一个 Lua 脚本中同时执行商品库存扣减和队伍人数增加
     * 
     * 实现逻辑：
     * 1. 幂等性检查（商品库存流水和队伍库存流水）
     * 2. 检查商品库存是否足够
     * 3. 检查队伍是否已满（如果teamStockKey不为空）
     * 4. 原子扣减商品库存
     * 5. 原子增加队伍人数（如果teamStockKey不为空）
     * 6. 记录流水
     */
    @Override
    public IRedisAdapter.StockDecreaseResult decreaseGoodsStockAndIncreaseTeamStock(
            String goodsStockKey, String goodsStockLogKey,
            String teamStockKey, String teamStockLogKey,
            String identifier, int goodsCount, int teamTargetCount) {
        
        // 如果不需要增加队伍人数，只扣减商品库存
        if (teamStockKey == null || teamStockLogKey == null) {
            Long goodsResult = decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, goodsCount);
            if (goodsResult == null || goodsResult < 0) {
                return new IRedisAdapter.StockDecreaseResult(null, null, false, "STOCK_NOT_ENOUGH");
            }
            return new IRedisAdapter.StockDecreaseResult(goodsResult, null, true, null);
        }

        // 需要同时扣减商品库存和增加队伍人数
        String luaScript = String.join("\n",
                "-- 幂等性检查：商品库存流水",
                "if redis.call('hexists', KEYS[2], ARGV[3]) == 1 then",
                "    return redis.error_reply('GOODS_OPERATION_ALREADY_EXECUTED')",
                "end",
                "",
                "-- 幂等性检查：队伍库存流水",
                "if redis.call('hexists', KEYS[4], ARGV[3]) == 1 then",
                "    return redis.error_reply('TEAM_OPERATION_ALREADY_EXECUTED')",
                "end",
                "",
                "-- 检查商品库存",
                "local goodsCurrent = redis.call('get', KEYS[1])",
                "if goodsCurrent == false then",
                "    return redis.error_reply('GOODS_STOCK_KEY_NOT_FOUND')",
                "end",
                "if tonumber(goodsCurrent) < tonumber(ARGV[1]) then",
                "    return redis.error_reply('GOODS_STOCK_NOT_ENOUGH')",
                "end",
                "",
                "-- 检查队伍库存（如果队伍库存不存在，初始化为0）",
                "local teamCurrent = redis.call('get', KEYS[3])",
                "if teamCurrent == false then",
                "    teamCurrent = '0'",
                "end",
                "local teamCurrentCount = tonumber(teamCurrent)",
                "local teamTarget = tonumber(ARGV[2])",
                "",
                "-- 检查队伍是否已满",
                "if teamCurrentCount >= teamTarget then",
                "    return redis.error_reply('TEAM_FULL')",
                "end",
                "",
                "-- 原子扣减商品库存",
                "local goodsNew = tonumber(goodsCurrent) - tonumber(ARGV[1])",
                "redis.call('set', KEYS[1], tostring(goodsNew))",
                "",
                "-- 原子增加队伍人数",
                "local teamNew = teamCurrentCount + 1",
                "redis.call('set', KEYS[3], tostring(teamNew))",
                "",
                "-- 记录商品库存流水",
                "local time = redis.call('time')",
                "local timestamp = (time[1] * 1000) + math.floor(time[2] / 1000)",
                "redis.call('hset', KEYS[2], ARGV[3], cjson.encode({",
                "    action = 'decrease',",
                "    from = goodsCurrent,",
                "    to = goodsNew,",
                "    change = ARGV[1],",
                "    by = ARGV[3],",
                "    timestamp = timestamp",
                "}))",
                "redis.call('expire', KEYS[2], 86400)",
                "",
                "-- 记录队伍库存流水",
                "redis.call('hset', KEYS[4], ARGV[3], cjson.encode({",
                "    action = 'increase_team',",
                "    from = teamCurrentCount,",
                "    to = teamNew,",
                "    change = 1,",
                "    targetCount = teamTarget,",
                "    by = ARGV[3],",
                "    timestamp = timestamp",
                "}))",
                "redis.call('expire', KEYS[4], 86400)",
                "",
                "-- 返回结果：商品剩余库存,队伍当前人数",
                "return {goodsNew, teamNew}"
        );

        try {
            List<Long> result = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    luaScript,
                    RScript.ReturnType.MULTI,
                    Arrays.asList(goodsStockKey, goodsStockLogKey, teamStockKey, teamStockLogKey),
                    goodsCount, teamTargetCount, identifier
            );

            if (result != null && result.size() >= 2) {
                Long goodsRemainingStock = result.get(0);
                Long teamCurrentCount = result.get(1);
                log.info("原子操作-扣减商品库存并增加队伍人数成功: goodsStockKey={}, teamStockKey={}, " +
                        "商品剩余库存={}, 队伍当前人数={}/{}",
                        goodsStockKey, teamStockKey, goodsRemainingStock, teamCurrentCount, teamTargetCount);
                return new IRedisAdapter.StockDecreaseResult(goodsRemainingStock, teamCurrentCount, true, null);
            } else {
                log.error("原子操作-返回结果格式错误: result={}", result);
                return new IRedisAdapter.StockDecreaseResult(null, null, false, "UNKNOWN_ERROR");
            }
        } catch (RedisException e) {
            String errorMsg = e.getMessage();
            log.error("原子操作-扣减商品库存并增加队伍人数失败: goodsStockKey={}, teamStockKey={}, error={}",
                    goodsStockKey, teamStockKey, errorMsg);
            
            // 解析错误码
            String errorCode = "UNKNOWN_ERROR";
            if (errorMsg != null) {
                if (errorMsg.contains("GOODS_STOCK_NOT_ENOUGH")) {
                    errorCode = "GOODS_STOCK_NOT_ENOUGH";
                } else if (errorMsg.contains("TEAM_FULL")) {
                    errorCode = "TEAM_FULL";
                } else if (errorMsg.contains("GOODS_OPERATION_ALREADY_EXECUTED") || 
                           errorMsg.contains("TEAM_OPERATION_ALREADY_EXECUTED")) {
                    errorCode = "OPERATION_ALREADY_EXECUTED";
                } else if (errorMsg.contains("GOODS_STOCK_KEY_NOT_FOUND")) {
                    errorCode = "GOODS_STOCK_KEY_NOT_FOUND";
                }
            }
            
            return new IRedisAdapter.StockDecreaseResult(null, null, false, errorCode);
        }
    }
}