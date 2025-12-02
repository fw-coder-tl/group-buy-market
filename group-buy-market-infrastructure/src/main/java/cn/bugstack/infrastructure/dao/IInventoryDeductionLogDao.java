package cn.bugstack.infrastructure.dao;

import cn.bugstack.infrastructure.dao.po.InventoryDeductionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IInventoryDeductionLogDao {

    /**
     * 插入流水
     */
    void insert(InventoryDeductionLog inventoryDeductionLog);

    /**
     * 根据订单ID查询流水
     */
    InventoryDeductionLog queryByOrderId(String orderId);

    /**
     * 根据时间范围查询流水（用于对账）
     */
    List<InventoryDeductionLog> queryByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询指定日期之前的流水（用于归档）
     */
    List<InventoryDeductionLog> queryLogsBeforeDate(@Param("archiveDate") java.util.Date archiveDate);

    /**
     * 根据ID删除流水
     */
    void deleteById(@Param("id") Long id);

    /**
     * 更新流水状态
     * @param orderId 订单ID
     * @param status 状态（TRY、CONFIRM、CANCEL）
     */
    void updateStatus(@Param("orderId") String orderId, @Param("status") String status);

}
