package cn.bugstack.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热点商品管理请求DTO
 * 
 * @author liang.tian
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotGoodsManageRequestDTO {

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 商品ID
     */
    private String goodsId;
}

