package cn.bugstack.api.dto;

import lombok.Data;

/**
 * @author liang.tian
 * @description 商品营销请求对象
 * @create 2025-011-06 12:19
 */
@Data
public class GoodsMarketRequestDTO {

    // 用户ID
    private String userId;
    // 渠道
    private String source;
    // 来源
    private String channel;
    // 商品ID
    private String goodsId;

}
