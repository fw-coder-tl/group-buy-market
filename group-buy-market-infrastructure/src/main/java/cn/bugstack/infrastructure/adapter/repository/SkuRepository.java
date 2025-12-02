package cn.bugstack.infrastructure.adapter.repository;

import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.infrastructure.dao.IInventoryDeductionLogDao;
import cn.bugstack.infrastructure.dao.ISkuActivityDao;
import cn.bugstack.infrastructure.dao.po.InventoryDeductionLog;
import cn.bugstack.infrastructure.dao.po.SkuActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Slf4j
@Repository
public class SkuRepository implements ISkuRepository {

    @Resource
    private ISkuActivityDao skuActivityDao;

    @Resource
    private IInventoryDeductionLogDao inventoryDeductionLogDao;

    @Resource
    private IRedisAdapter redisAdapter;

    // 商品库存 Redis 前缀
    private static final String GOODS_STOCK_KEY_PREFIX = "group_buy_market_goods_stock_";
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    private static final int MAX_RETRY = 3;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean decreaseSkuStock(Long activityId, String goodsId, Integer quantity, String orderId, String userId) {
        // 1. 幂等性检查：先查询流水表
        InventoryDeductionLog existingLog = inventoryDeductionLogDao.queryByOrderId(orderId);
        if (existingLog != null) {
            log.info("SKU库存已扣减（幂等），跳过: orderId={}, activityId={}, goodsId={}",
                    orderId, activityId, goodsId);
            return true; // 已扣减过，直接返回成功
        }

        // 2. 乐观锁重试逻辑
        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                // 2.1 查询当前库存
                SkuActivity skuActivity = skuActivityDao.querySkuActivity(activityId, goodsId);
                if (skuActivity == null) {
                    log.error("SKU不存在: activityId={}, goodsId={}", activityId, goodsId);
                    return false;
                }

                // 2.2 检查可售库存
                if (skuActivity.getSaleableInventory() < quantity) {
                    log.warn("SKU库存不足: activityId={}, goodsId={}, saleable={}, need={}",
                            activityId, goodsId, skuActivity.getSaleableInventory(), quantity);
                    return false;
                }

                int beforeSaleable = skuActivity.getSaleableInventory();
                int beforeFrozen = skuActivity.getFrozenInventory();
                int lockVersion = skuActivity.getLockVersion();

                // 3. 原子操作：扣减库存 + 插入流水
                // 3.1 扣减库存（乐观锁）- TCC模型：下单时只增加冻结库存，不减少可售库存
                int updateCount = skuActivityDao.decreaseSkuStock(activityId, goodsId, quantity, lockVersion);

                if (updateCount > 0) {
                    // 3.2 插入扣减流水（唯一索引保证幂等）
                    // ⭐ TCC模型：下单时只增加冻结库存，可售库存不变
                    // afterSaleable = beforeSaleable（不是 beforeSaleable - quantity）
                    InventoryDeductionLog logEntry = InventoryDeductionLog.builder()
                            .orderId(orderId)
                            .userId(userId) // 使用传入的 userId
                            .activityId(activityId)
                            .goodsId(goodsId)
                            .quantity(quantity)
                            .beforeSaleable(beforeSaleable)
                            .afterSaleable(beforeSaleable) // ⭐ 修复：TCC模型，下单时可售库存不变
                            .beforeFrozen(beforeFrozen)
                            .afterFrozen(beforeFrozen + quantity)
                            .lockVersion(lockVersion)
                            .status("SUCCESS")
                            .build();

                    try {
                        inventoryDeductionLogDao.insert(logEntry);
                    } catch (DuplicateKeyException e) {
                        // 唯一索引冲突，说明并发情况下已经有其他线程扣减成功并插入了流水
                        log.warn("库存扣减流水重复（并发幂等）: orderId={}", orderId);
                        return true;
                    }

                    log.info("扣减SKU库存成功: orderId={}, activityId={}, goodsId={}, quantity={}, version={}",
                            orderId, activityId, goodsId, quantity, lockVersion);
                    return true;
                }

                log.warn("扣减SKU库存失败（版本冲突），重试: activityId={}, goodsId={}, retry={}/{}",
                        activityId, goodsId, i + 1, MAX_RETRY);

            } catch (Exception e) {
                log.error("扣减SKU库存异常: activityId={}, goodsId={}, retry={}/{}",
                        activityId, goodsId, i + 1, MAX_RETRY, e);
            }

            // 短暂等待后重试
            try {
                Thread.sleep(10 * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.error("扣减SKU库存失败，已达最大重试次数: activityId={}, goodsId={}", activityId, goodsId);
        return false;
    }

    @Override
    public boolean confirmSkuStock(Long activityId, String goodsId, Integer quantity) {
        return skuActivityDao.confirmSkuStock(activityId, goodsId, quantity) > 0;
    }

    @Override
    public boolean releaseSkuStock(Long activityId, String goodsId, Integer quantity) {
        return skuActivityDao.releaseSkuStock(activityId, goodsId, quantity) > 0;
    }

    /**
     * TCC Try：尝试扣减库存（Redis 预扣减，不扣减数据库）
     * 
     * 对标 NFTurbo 的 tryDecreaseInventory
     * 
     * 实现：
     * 1. 在 Redis 中预扣减库存
     * 2. 记录 Redis 流水
     * 3. 不扣减数据库库存
     */
    @Override
    public boolean tryDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId, String userId) {
        try {
            // 1. 构建 Redis Key
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            String identifier = "TRY_" + userId + "_" + orderId;

            // 2. 在 Redis 中预扣减库存（记录流水）
            Long result = redisAdapter.decreaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, quantity);
            
            if (result == null || result < 0) {
                log.warn("TCC Try-Redis预扣减库存失败: orderId={}, activityId={}, goodsId={}, result={}", 
                        orderId, activityId, goodsId, result);
                return false;
            }

            log.info("TCC Try-Redis预扣减库存成功: orderId={}, activityId={}, goodsId={}, quantity={}, 剩余库存={}", 
                    orderId, activityId, goodsId, quantity, result);
            return true;
        } catch (Exception e) {
            log.error("TCC Try-Redis预扣减库存异常: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId, e);
            return false;
        }
    }

    /**
     * TCC Confirm：确认扣减库存（真正扣减数据库库存）
     * 
     * 对标 NFTurbo 的 confirmDecreaseInventory
     * 
     * 实现：
     * 1. 扣减数据库可售库存
     * 2. 减少冻结库存
     * 3. 更新流水状态为 CONFIRM
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId) {
        // 1. 查询流水
        InventoryDeductionLog logEntry = inventoryDeductionLogDao.queryByOrderId(orderId);
        if (logEntry == null) {
            log.warn("TCC Confirm-未找到库存扣减流水: orderId={}", orderId);
            return false;
        }

        // 2. 检查是否已确认
        if ("CONFIRM".equals(logEntry.getStatus())) {
            log.info("TCC Confirm-库存已确认（幂等）: orderId={}", orderId);
            return true;
        }

        // 3. 确认扣减库存（真正扣减可售库存，减少冻结库存）
        int updateCount = skuActivityDao.confirmSkuStock(activityId, goodsId, quantity);
        if (updateCount <= 0) {
            log.error("TCC Confirm-确认扣减库存失败: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId);
            return false;
        }

        // 4. 更新流水状态为 CONFIRM
        inventoryDeductionLogDao.updateStatus(orderId, "CONFIRM");

        log.info("TCC Confirm-确认扣减库存成功: orderId={}, activityId={}, goodsId={}, quantity={}", 
                orderId, activityId, goodsId, quantity);
        return true;
    }

    /**
     * TCC Cancel：取消扣减库存（回滚 Redis 库存）
     * 
     * 对标 NFTurbo 的 cancelDecreaseInventory
     * 
     * 实现：
     * 1. 回滚 Redis 库存
     * 2. 删除 Redis 流水
     * 3. 释放数据库冻结库存
     */
    @Override
    public boolean cancelDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId) {
        // 1. 释放数据库冻结库存
        int updateCount = skuActivityDao.releaseSkuStock(activityId, goodsId, quantity);
        if (updateCount <= 0) {
            log.warn("TCC Cancel-释放冻结库存失败: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId);
        }

        // 2. 更新流水状态为 CANCEL
        inventoryDeductionLogDao.updateStatus(orderId, "CANCEL");

        // 3. 回滚 Redis 库存
        try {
            String goodsStockKey = GOODS_STOCK_KEY_PREFIX + activityId + "_" + goodsId;
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + activityId + "_" + goodsId;
            String identifier = "CANCEL_" + orderId;
            
            Long rollbackResult = redisAdapter.increaseStockWithLog(goodsStockKey, goodsStockLogKey, identifier, quantity);
            log.info("TCC Cancel-Redis库存回滚成功: orderId={}, activityId={}, goodsId={}, quantity={}, 回滚后库存={}", 
                    orderId, activityId, goodsId, quantity, rollbackResult);
        } catch (Exception e) {
            log.error("TCC Cancel-Redis库存回滚失败: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId, e);
        }

        log.info("TCC Cancel-取消扣减库存成功: orderId={}, activityId={}, goodsId={}, quantity={}", 
                orderId, activityId, goodsId, quantity);
        return true;
    }
}
