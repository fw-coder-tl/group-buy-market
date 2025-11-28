package cn.bugstack.infrastructure.adapter.repository;

import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;
import cn.bugstack.infrastructure.dao.IInventoryDeductionLogDao;
import cn.bugstack.infrastructure.dao.po.InventoryDeductionLog;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

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
}

