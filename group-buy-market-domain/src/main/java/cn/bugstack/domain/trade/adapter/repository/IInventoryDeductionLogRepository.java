package cn.bugstack.domain.trade.adapter.repository;

import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;

/**
 * 库存扣减流水仓储接口（Domain层）
 */
public interface IInventoryDeductionLogRepository {

    /**
     * 根据订单ID查询流水
     * 
     * @param orderId 订单ID（幂等号）
     * @return 库存扣减流水，不存在返回 null
     */
    InventoryDeductionLogEntity queryByOrderId(String orderId);
}

