package cn.bugstack.infrastructure.adapter.repository;

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
}
