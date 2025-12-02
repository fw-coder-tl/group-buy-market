package cn.bugstack.infrastructure.adapter.repository;

import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;
import cn.bugstack.infrastructure.dao.IInventoryDeductionLogDao;
import cn.bugstack.infrastructure.dao.po.InventoryDeductionLog;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 库存扣减流水仓储实现（Infrastructure层）
 */
@Repository
public class InventoryDeductionLogRepository implements IInventoryDeductionLogRepository {

    @Resource
    private IInventoryDeductionLogDao inventoryDeductionLogDao;

    @Override
    public InventoryDeductionLogEntity queryByOrderId(String orderId) {
        InventoryDeductionLog po = inventoryDeductionLogDao.queryByOrderId(orderId);
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

