package cn.bugstack.infrastructure.adapter.repository;

import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;
import cn.bugstack.infrastructure.dao.IInventoryDeductionLogDao;
import cn.bugstack.infrastructure.dao.po.InventoryDeductionLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 库存扣减流水仓储实现（Infrastructure层）
 * 
 * 支持冷热分离：
 * - 热数据：分片表中的数据（最近1个月）
 * - 冷数据：归档表中的数据（1个月前的历史数据）
 */
@Slf4j
@Repository
public class InventoryDeductionLogRepository implements IInventoryDeductionLogRepository {

    @Resource
    private IInventoryDeductionLogDao inventoryDeductionLogDao;

    @Override
    public InventoryDeductionLogEntity queryByOrderId(String orderId) {
        // 1. 先查热数据表（分片表）
        InventoryDeductionLog po = inventoryDeductionLogDao.queryByOrderId(orderId);
        
        // 2. 如果热数据表不存在，再查归档表（冷热分离）
        if (po == null) {
            try {
                po = inventoryDeductionLogDao.queryByOrderIdFromArchive(orderId);
            } catch (Exception e) {
                // 归档表可能不存在，记录日志但不抛出异常
                log.warn("查询归档表失败（表可能不存在）: orderId={}, error={}", 
                        orderId, e.getMessage());
                return null;
            }
        }
        
        if (po == null) {
            return null;
        }
        
        // PO 转 Entity
        InventoryDeductionLogEntity entity = new InventoryDeductionLogEntity();
        BeanUtils.copyProperties(po, entity);
        return entity;
    }

    /**
     * 查询指定日期之前的流水（用于归档）
     * @param archiveDate 归档日期
     * @return 需要归档的流水列表
     */
    public List<InventoryDeductionLog> queryLogsBeforeDate(Date archiveDate) {
        return inventoryDeductionLogDao.queryLogsBeforeDate(archiveDate);
    }

    /**
     * 根据ID删除流水
     * @param id 流水ID
     */
    public void deleteById(Long id) {
        inventoryDeductionLogDao.deleteById(id);
    }
}

