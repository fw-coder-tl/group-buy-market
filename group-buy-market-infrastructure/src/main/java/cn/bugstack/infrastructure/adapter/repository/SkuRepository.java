package cn.bugstack.infrastructure.adapter.repository;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.infrastructure.dao.ISkuActivityDao;
import cn.bugstack.infrastructure.dao.po.SkuActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

@Slf4j
@Repository
public class SkuRepository implements ISkuRepository {

    @Resource
    private ISkuActivityDao skuActivityDao;

    @Override
    public boolean decreaseSkuStock(Long activityId, String goodsId, Integer quantity) {
        // 重试3次（乐观锁冲突重试）
        for (int i = 0; i < 3; i++) {
            try {
                // 查询当前版本号
                SkuActivity skuActivity = skuActivityDao.querySkuActivity(activityId, goodsId);
                if (skuActivity == null) {
                    log.error("SKU不存在: activityId={}, goodsId={}", activityId, goodsId);
                    return false;
                }

                // 检查库存是否充足
                if (skuActivity.getSaleableInventory() - skuActivity.getFrozenInventory() < quantity) {
                    log.warn("SKU库存不足: activityId={}, goodsId={}, 可用库存={}",
                            activityId, goodsId,
                            skuActivity.getSaleableInventory() - skuActivity.getFrozenInventory());
                    return false;
                }

                // 乐观锁扣减
                int updateCount = skuActivityDao.decreaseSkuStock(
                        activityId, goodsId, quantity, skuActivity.getLockVersion()
                );

                if (updateCount > 0) {
                    log.info("扣减SKU库存成功: activityId={}, goodsId={}, quantity={}, version={}",
                            activityId, goodsId, quantity, skuActivity.getLockVersion());
                    return true;
                }

                // 版本号冲突，重试
                log.warn("SKU库存扣减版本冲突，重试第{}次: activityId={}, goodsId={}", i + 1, activityId, goodsId);
                Thread.sleep(50); // 短暂休眠后重试

            } catch (Exception e) {
                log.error("扣减SKU库存异常: activityId={}, goodsId={}", activityId, goodsId, e);
                return false;
            }
        }

        log.error("扣减SKU库存失败，超过重试次数: activityId={}, goodsId={}", activityId, goodsId);
        return false;
    }

    @Override
    public boolean confirmSkuStock(Long activityId, String goodsId, Integer quantity) {
        try {
            int updateCount = skuActivityDao.confirmSkuStock(activityId, goodsId, quantity);
            if (updateCount > 0) {
                log.info("确认SKU库存成功: activityId={}, goodsId={}, quantity={}", activityId, goodsId, quantity);
                return true;
            }
            log.warn("确认SKU库存失败: activityId={}, goodsId={}", activityId, goodsId);
            return false;
        } catch (Exception e) {
            log.error("确认SKU库存异常: activityId={}, goodsId={}", activityId, goodsId, e);
            return false;
        }
    }

    @Override
    public boolean releaseSkuStock(Long activityId, String goodsId, Integer quantity) {
        try {
            int updateCount = skuActivityDao.releaseSkuStock(activityId, goodsId, quantity);
            if (updateCount > 0) {
                log.info("释放SKU库存成功: activityId={}, goodsId={}, quantity={}", activityId, goodsId, quantity);
                return true;
            }
            log.warn("释放SKU库存失败: activityId={}, goodsId={}", activityId, goodsId);
            return false;
        } catch (Exception e) {
            log.error("释放SKU库存异常: activityId={}, goodsId={}", activityId, goodsId, e);
            return false;
        }
    }
}