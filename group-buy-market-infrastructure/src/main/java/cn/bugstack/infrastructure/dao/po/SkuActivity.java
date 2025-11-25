package cn.bugstack.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkuActivity {
    /** 活动ID */
    private Long activityId;
    
    /** 商品ID */
    private String goodsId;
    
    /** 可售库存 */
    private Integer saleableInventory;
    
    /** 冻结库存（已下单未支付）*/
    private Integer frozenInventory;
    
    /** 乐观锁版本号 */
    private Integer lockVersion;
}