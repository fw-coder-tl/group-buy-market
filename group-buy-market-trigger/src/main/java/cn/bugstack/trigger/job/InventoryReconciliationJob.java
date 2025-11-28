package cn.bugstack.trigger.job;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;
import cn.bugstack.domain.trade.model.vo.RedisStockLogVO;
import com.alibaba.fastjson.JSON;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 库存对账任务（参考 NFTurbo）
 * 
 * 功能：
 * 1. 扫描 Redis 库存扣减流水
 * 2. 根据 orderId（幂等号）查询数据库流水
 * 3. 对比 Redis 和数据库的扣减数量是否一致
 * 4. 处理不一致的情况（告警或补偿）
 * 
 * 执行频率：在 XXL-Job 管理平台配置（建议每分钟执行一次）
 * 时间阈值：只处理3秒之前的数据，避免和旁路验证冲突
 */
@Slf4j
@Component
public class InventoryReconciliationJob {

    // 商品库存流水前缀
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";
    // 对账时间阈值（超过此时间的流水才进行对账，避免检查刚生成的流水）
    // 参考NFTurbo实现：只处理3秒之前的数据，避免出现清理后导致重复扣减
    private static final long CHECK_THRESHOLD_MS = 3000L;
    // 分布式锁 key
    private static final String LOCK_KEY = "group_buy_market_inventory_reconciliation_job";

    @Resource
    private IRedisAdapter redisAdapter;
    @Resource
    private IInventoryDeductionLogRepository inventoryDeductionLogRepository;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 库存对账任务（参考 NFTurbo）
     * 
     * 执行频率：在 XXL-Job 管理平台配置（建议每分钟执行一次）
     * 时间阈值：只处理3秒之前的数据，避免和旁路验证冲突
     */
    @XxlJob("inventoryReconciliationJob")
    public ReturnT<String> exec() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 0, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("对账任务获取锁失败，跳过本次执行");
                return ReturnT.SUCCESS;
            }
            
            log.info("库存对账任务开始执行");
            
            // 1. 扫描所有商品库存流水
            Iterable<String> logKeys = redisAdapter.scanStockLogKeys(GOODS_STOCK_LOG_KEY_PREFIX + "*");
            int totalLogs = 0;
            int reconciledLogs = 0;
            int inconsistentLogs = 0;
            int missingDbLogs = 0;
            
            for (String logKey : logKeys) {
                Map<String, String> redisLogs = redisAdapter.getAllStockDecreaseLogs(logKey);
                if (redisLogs == null || redisLogs.isEmpty()) {
                    continue;
                }
                
                // 2. 对每条 Redis 流水进行对账
                for (Map.Entry<String, String> entry : redisLogs.entrySet()) {
                    String identifier = entry.getKey();
                    String value = entry.getValue();
                    
                    totalLogs++;
                    ReconciliationResult result = reconcileLog(logKey, identifier, value);
                    
                    switch (result) {
                        case RECONCILED:
                            reconciledLogs++;
                            break;
                        case INCONSISTENT:
                            inconsistentLogs++;
                            break;
                        case MISSING_DB:
                            missingDbLogs++;
                            break;
                        case SKIPPED:
                            // 跳过（时间太短，等待下次对账）
                            break;
                    }
                }
            }
            
            log.info("库存对账任务执行完成: 总流水={}, 已对账={}, 不一致={}, 缺失DB流水={}",
                    totalLogs, reconciledLogs, inconsistentLogs, missingDbLogs);
            
            return ReturnT.SUCCESS;
                    
        } catch (Exception e) {
            log.error("库存对账任务执行异常", e);
            return ReturnT.FAIL;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 对单条流水进行对账
     * 
     * @param logKey Redis Hash 的 key
     * @param identifier Redis Hash 的 field（DECREASE_userId_orderId）
     * @param value Redis Hash 的 value（JSON 字符串）
     * @return 对账结果
     */
    private ReconciliationResult reconcileLog(String logKey, String identifier, String value) {
        try {
            // 1. 解析 Redis 流水
            RedisStockLogVO redisLog = JSON.parseObject(value, RedisStockLogVO.class);
            if (redisLog == null || !"decrease".equalsIgnoreCase(redisLog.getAction())) {
                // 只处理扣减操作，忽略回滚等其他操作
                return ReconciliationResult.SKIPPED;
            }
            
            // 2. 检查时间阈值（避免检查刚生成的流水）
            long timestamp = redisLog.getTimestamp() != null ? redisLog.getTimestamp() : 0;
            if (System.currentTimeMillis() - timestamp < CHECK_THRESHOLD_MS) {
                return ReconciliationResult.SKIPPED;
            }
            
            // 3. 提取 orderId（幂等号）
            String orderId = redisLog.extractOrderId();
            if (orderId == null) {
                log.warn("对账失败-无法提取orderId: identifier={}", identifier);
                return ReconciliationResult.SKIPPED;
            }
            
            // 4. 查询数据库流水
            InventoryDeductionLogEntity dbLog = inventoryDeductionLogRepository.queryByOrderId(orderId);
            
            if (dbLog == null) {
                // 情况1：Redis 有流水，数据库无流水
                log.warn("对账不一致-数据库流水缺失: orderId={}, redisChange={}, identifier={}",
                        orderId, redisLog.getChangeAsInteger(), identifier);
                // TODO: 可以在这里触发告警或补偿逻辑
                return ReconciliationResult.MISSING_DB;
            }
            
            // 5. ⭐ 验证扣减数量一致性（防御性检查）
            // 注意：在当前设计中，每次扣减都是 quantity=1，且有幂等性保护，数量应该总是一致的
            // 但保留此检查是为了：
            // 1. 防御性编程：防止未来代码变更（如支持批量扣减）导致的不一致
            // 2. 数据完整性验证：确保流水记录的数据是正确的
            // 3. 异常检测：如果出现数量不一致，说明系统有bug或异常（如数据被手动修改）
            Integer redisChange = redisLog.getChangeAsInteger();
            Integer dbQuantity = dbLog.getQuantity();
            
            if (redisChange == null || dbQuantity == null) {
                log.warn("对账失败-扣减数量为空: orderId={}, redisChange={}, dbQuantity={}",
                        orderId, redisChange, dbQuantity);
                return ReconciliationResult.SKIPPED;
            }
            
            if (!redisChange.equals(dbQuantity)) {
                // ⚠️ 数量不一致：这不应该发生，说明系统有bug或数据被手动修改
                // 可能的原因：
                // 1. 代码bug：Redis扣减和数据库扣减的数量不一致
                // 2. 数据被手动修改：数据库或Redis的数据被手动修改
                // 3. 未来支持批量扣减：如果未来支持批量扣减，可能出现不一致
                log.error("对账不一致-扣减数量不匹配（异常情况）: orderId={}, redisChange={}, dbQuantity={}, identifier={}",
                        orderId, redisChange, dbQuantity, identifier);
                // TODO: 可以在这里触发告警或补偿逻辑
                return ReconciliationResult.INCONSISTENT;
            }
            
            // 6. 验证冻结库存变化一致性（TCC模型：下单时只增加冻结库存）
            // 冻结库存增加量 = 扣减数量
            Integer frozenChange = dbLog.getAfterFrozen() != null && dbLog.getBeforeFrozen() != null
                    ? dbLog.getAfterFrozen() - dbLog.getBeforeFrozen()
                    : null;
            
            if (frozenChange == null) {
                log.warn("对账失败-冻结库存数据为空: orderId={}, beforeFrozen={}, afterFrozen={}",
                        orderId, dbLog.getBeforeFrozen(), dbLog.getAfterFrozen());
                return ReconciliationResult.SKIPPED;
            }
            
            if (!frozenChange.equals(dbQuantity)) {
                // 情况3：冻结库存变化量与扣减数量不一致
                log.error("对账不一致-冻结库存变化不匹配: orderId={}, quantity={}, frozenChange={}, " +
                                "beforeFrozen={}, afterFrozen={}, identifier={}",
                        orderId, dbQuantity, frozenChange,
                        dbLog.getBeforeFrozen(), dbLog.getAfterFrozen(), identifier);
                // TODO: 可以在这里触发告警或补偿逻辑
                return ReconciliationResult.INCONSISTENT;
            }
            
            // 7. ⭐ 参考 NFTurbo：对账只检查下单时的库存扣减（TRY_SALE），不检查支付确认后的库存变化
            // 原因：用户下单后可能立即支付，导致可售库存立即扣减，但这是支付确认操作，不在对账范围内
            // 对账只验证：1) 扣减数量一致 2) 冻结库存变化一致
            // 不验证可售库存变化，因为支付后可能会变化
            
            // 8. 所有验证通过，数据一致，删除 Redis 流水
            redisAdapter.removeStockDecreaseLog(logKey, identifier);
            log.debug("对账成功-数据一致，已清理Redis流水: orderId={}, quantity={}, frozenChange={}",
                    orderId, dbQuantity, frozenChange);
            return ReconciliationResult.RECONCILED;
            
        } catch (Exception e) {
            log.error("对账异常: identifier={}, error={}", identifier, e.getMessage(), e);
            return ReconciliationResult.SKIPPED;
        }
    }

    /**
     * 对账结果枚举
     */
    private enum ReconciliationResult {
        /** 已对账（数据一致，已清理） */
        RECONCILED,
        /** 不一致（扣减数量不匹配） */
        INCONSISTENT,
        /** 缺失DB流水（Redis有，DB无） */
        MISSING_DB,
        /** 跳过（时间太短或其他原因） */
        SKIPPED
    }
}

