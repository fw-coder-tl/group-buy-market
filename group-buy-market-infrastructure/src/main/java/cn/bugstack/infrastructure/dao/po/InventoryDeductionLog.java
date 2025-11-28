package cn.bugstack.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 库存扣减流水
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InventoryDeductionLog {

    /** 主键 */
    private Long id;
    /** 订单ID（幂等性标识） */
    private String orderId;
    /** 用户ID */
    private String userId;
    /** 活动ID */
    private Long activityId;
    /** 商品ID */
    private String goodsId;
    /** 扣减数量 */
    private Integer quantity;
    /** 扣减前可售库存 */
    private Integer beforeSaleable;
    /** 扣减后可售库存 */
    private Integer afterSaleable;
    /** 扣减前冻结库存 */
    private Integer beforeFrozen;
    /** 扣减后冻结库存 */
    private Integer afterFrozen;
    /** 扣减时的版本号 */
    private Integer lockVersion;
    /** 状态：SUCCESS-成功 */
    private String status;
    /** 创建时间 */
    private Date createTime;

}
