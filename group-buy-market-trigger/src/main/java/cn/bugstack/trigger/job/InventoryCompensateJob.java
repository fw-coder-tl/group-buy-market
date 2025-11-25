package cn.bugstack.trigger.job;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 库存扣减补偿任务
 */
@Slf4j
@Service
public class InventoryCompensateJob {

    private static final String STOCK_KEY_PREFIX = "group_buy_market_team_stock_key_";
    private static final String STOCK_LOG_KEY_PREFIX = "group_buy_market_team_stock_log_";
    private static final long CHECK_THRESHOLD_MS = 5000L;
    private static final String LOCK_KEY = "group_buy_market_inventory_compensate_job";

    @Resource
    private IRedisAdapter redisAdapter;
    @Resource
    private ITradeRepository tradeRepository;
    @Resource
    private RedissonClient redissonClient;

    @Scheduled(cron = "0/30 * * * * ?")
    public void exec() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            Iterable<String> logKeys = redisAdapter.scanStockLogKeys(STOCK_LOG_KEY_PREFIX + "*");
            for (String logKey : logKeys) {
                Map<String, String> logs = redisAdapter.getAllStockDecreaseLogs(logKey);
                if (logs == null || logs.isEmpty()) {
                    continue;
                }
                String teamId = logKey.substring(STOCK_LOG_KEY_PREFIX.length());
                String stockKey = STOCK_KEY_PREFIX + teamId;
                logs.forEach((identifier, value) -> handleLogEntry(stockKey, logKey, teamId, identifier, value));
            }
        } catch (Exception e) {
            log.error("库存补偿任务执行异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void handleLogEntry(String stockKey, String logKey, String teamId, String identifier, String value) {
        if (value == null) {
            return;
        }
        JSONObject jsonObject = JSON.parseObject(value);
        if (!"decrease".equalsIgnoreCase(jsonObject.getString("action"))) {
            return;
        }
        long timestamp = jsonObject.getLongValue("timestamp");
        if (System.currentTimeMillis() - timestamp < CHECK_THRESHOLD_MS) {
            return;
        }
        String[] parts = identifier.split("_", 3);
        if (parts.length < 3) {
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            return;
        }
        String userId = parts[1];
        String orderId = parts[2];  // 现在identifier中使用的是orderId而不是outTradeNo
        // 使用orderId查询订单
        MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
        if (order != null) {
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            log.info("补偿检查：已落库，清理流水 teamId={}, orderId={}", teamId, orderId);
            return;
        }
        int change = jsonObject.getIntValue("change");
        String rollbackIdentifier = identifier.replaceFirst("DECREASE_", "INCREASE_");
        redisAdapter.increaseStockWithLog(stockKey, logKey, rollbackIdentifier, change);
        redisAdapter.removeStockDecreaseLog(logKey, identifier);
        log.warn("补偿执行：回滚库存成功 teamId={}, orderId={}, rollback={}", teamId, orderId, change);
    }
}

