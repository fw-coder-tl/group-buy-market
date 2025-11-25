package cn.bugstack.domain.trade.model.valobj;

import lombok.*;

/**
 * 拼团退单消息
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/29 09:15
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TeamRefundSuccess {
    /** 用户ID */
    private String userId;

    /** 活动ID */
    private Long activityId;

    /** 队伍ID */
    private String teamId;

    /** 订单ID */
    private String orderId;

    /** 外部交易订单号 */
    private String outTradeNo;

    /** 商品ID（新增） */
    private String goodsId;

    /** 退单类型 */
    private String type;
}
