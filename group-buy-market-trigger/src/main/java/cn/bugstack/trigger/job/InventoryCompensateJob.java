package cn.bugstack.trigger.job;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 库存扣减补偿任务
 */
/**
 * 库存扣减补偿任务（参考 NFTurbo）
 * 
 * 功能：
 * 1. 扫描 Redis 商品库存扣减流水
 * 2. 扫描 Redis 队伍库存扣减流水
 * 3. 检查订单是否已落库
 * 4. 如果订单未落库，回滚库存（补偿 Redis 扣减）
 * 5. 删除Redis流水
 * 
 * 注意：此任务只处理订单未落库的情况（回滚库存）
 * 对账不一致的情况（MISSING_DB、INCONSISTENT）由对账任务记录告警，需要人工介入
 * 
 * 完善：同时处理商品库存和队伍库存，确保订单创建失败时所有库存都被回滚
 * 
 * 执行频率：在 XXL-Job 管理平台配置（建议每30秒执行一次）
 * 时间阈值：只处理5秒之前的数据，给订单落库更多时间
 */
@Slf4j
@Component
public class InventoryCompensateJob {

    // 商品库存相关前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";
    
    // 队伍库存相关前缀
    private static final String TEAM_STOCK_KEY_PREFIX = "group_buy_market_team_stock_key_";
    private static final String TEAM_STOCK_LOG_KEY_PREFIX = "group_buy_market_team_stock_log_";
    
    private static final long CHECK_THRESHOLD_MS = 5000L;
    private static final String LOCK_KEY = "group_buy_market_inventory_compensate_job";

    @Resource
    private IRedisAdapter redisAdapter;
    @Resource
    private ITradeRepository tradeRepository;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 库存扣减补偿任务
     * 
     * 执行频率：在 XXL-Job 管理平台配置（建议每30秒执行一次）
     */
    @XxlJob("inventoryCompensateJob")
    public ReturnT<String> exec() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 0, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("补偿任务获取锁失败，跳过本次执行");
                return ReturnT.SUCCESS;
            }
            
            log.info("库存补偿任务开始执行");
            
            // 1. 检查商品库存流水
            checkAndCompensateGoodsStock();
            
            // 2. 检查队伍库存流水
            checkAndCompensateTeamStock();
            
            log.info("库存补偿任务执行完成");
            return ReturnT.SUCCESS;
                    
        } catch (Exception e) {
            log.error("库存补偿任务执行异常", e);
            return ReturnT.FAIL;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 检查并补偿商品库存
     */
    private void checkAndCompensateGoodsStock() {
        log.info("开始检查商品库存流水");
        Iterable<String> logKeys = redisAdapter.scanStockLogKeys(GOODS_STOCK_LOG_KEY_PREFIX + "*");
        int processedCount = 0;
        
        for (String logKey : logKeys) {
            Map<String, String> logs = redisAdapter.getAllStockDecreaseLogs(logKey);
            if (logs == null || logs.isEmpty()) {
                continue;
            }
            
            // 解析 logKey 获取 activityId 和 goodsId
            // logKey 格式：group_buy_market_goods_stock_log_{activityId}_{goodsId}
            String keyWithoutPrefix = logKey.replace(GOODS_STOCK_LOG_KEY_PREFIX, "");
            String[] parts = keyWithoutPrefix.split("_", 2);
            if (parts.length < 2) {
                log.warn("商品库存流水key格式错误，跳过: logKey={}", logKey);
                continue;
            }
            
            String activityId = parts[0];
            String goodsId = parts[1];
            String stockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            
            for (Map.Entry<String, String> entry : logs.entrySet()) {
                String identifier = entry.getKey();
                String value = entry.getValue();
                handleGoodsStockLogEntry(stockKey, logKey, activityId, goodsId, identifier, value);
                processedCount++;
            }
        }
        
        log.info("商品库存流水检查完成，处理数量: {}", processedCount);
    }

    /**
     * 检查并补偿队伍库存
     */
    private void checkAndCompensateTeamStock() {
        log.info("开始检查队伍库存流水");
        Iterable<String> logKeys = redisAdapter.scanStockLogKeys(TEAM_STOCK_LOG_KEY_PREFIX + "*");
        int processedCount = 0;
        
        for (String logKey : logKeys) {
            Map<String, String> logs = redisAdapter.getAllStockDecreaseLogs(logKey);
            if (logs == null || logs.isEmpty()) {
                continue;
            }
            
            // 解析 logKey 获取 activityId 和 teamId
            // logKey 格式：group_buy_market_team_stock_log_{activityId}_{teamId}
            String keyWithoutPrefix = logKey.replace(TEAM_STOCK_LOG_KEY_PREFIX, "");
            String[] parts = keyWithoutPrefix.split("_", 2);
            if (parts.length < 2) {
                log.warn("队伍库存流水key格式错误，跳过: logKey={}", logKey);
                continue;
            }
            
            String activityId = parts[0];
            String teamId = parts[1];
            String stockKey = TEAM_STOCK_KEY_PREFIX + activityId + "_" + teamId;
            
            for (Map.Entry<String, String> entry : logs.entrySet()) {
                String identifier = entry.getKey();
                String value = entry.getValue();
                handleTeamStockLogEntry(stockKey, logKey, activityId, teamId, identifier, value);
                processedCount++;
            }
        }
        
        log.info("队伍库存流水检查完成，处理数量: {}", processedCount);
    }

    /**
     * 处理商品库存流水
     */
    private void handleGoodsStockLogEntry(String stockKey, String logKey, String activityId, String goodsId, 
                                         String identifier, String value) {
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
            log.warn("商品库存流水identifier格式错误，清理流水: identifier={}, logKey={}", identifier, logKey);
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            return;
        }
        
        String userId = parts[1];
        String orderId = parts[2];
        
        // 检查订单是否已落库
        MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
        if (order != null) {
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            log.info("商品库存补偿检查：已落库，清理流水 activityId={}, goodsId={}, orderId={}", 
                    activityId, goodsId, orderId);
            return;
        }
        
        // 订单未落库，回滚商品库存
        int change = jsonObject.getIntValue("change");
        String rollbackIdentifier = identifier.replaceFirst("DECREASE_", "INCREASE_");
        redisAdapter.increaseStockWithLog(stockKey, logKey, rollbackIdentifier, change);
        redisAdapter.removeStockDecreaseLog(logKey, identifier);
        log.warn("商品库存补偿执行：回滚库存成功 activityId={}, goodsId={}, orderId={}, rollback={}", 
                activityId, goodsId, orderId, change);
    }

    /**
     * 处理队伍库存流水
     */
    private void handleTeamStockLogEntry(String stockKey, String logKey, String activityId, String teamId, 
                                        String identifier, String value) {
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
            log.warn("队伍库存流水identifier格式错误，清理流水: identifier={}, logKey={}", identifier, logKey);
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            return;
        }
        
        String userId = parts[1];
        String orderId = parts[2];
        
        // 检查订单是否已落库
        MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
        if (order != null) {
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            log.info("队伍库存补偿检查：已落库，清理流水 activityId={}, teamId={}, orderId={}", 
                    activityId, teamId, orderId);
            return;
        }
        
        // 订单未落库，回滚队伍库存
        int change = jsonObject.getIntValue("change");
        String rollbackIdentifier = identifier.replaceFirst("DECREASE_", "INCREASE_");
        redisAdapter.increaseStockWithLog(stockKey, logKey, rollbackIdentifier, change);
        redisAdapter.removeStockDecreaseLog(logKey, identifier);
        log.warn("队伍库存补偿执行：回滚库存成功 activityId={}, teamId={}, orderId={}, rollback={}", 
                activityId, teamId, orderId, change);
    }
}

