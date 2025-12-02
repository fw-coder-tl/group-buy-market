package cn.bugstack.domain.trade.model.valobj;

import lombok.*;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易订单状态枚举
 * @create 2025-01-11 10:21
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum TradeOrderStatusEnumVO {

    CREATE(0, "初始创建"),
    COMPLETE(1, "消费完成"),
    CLOSE(2, "用户退单"),
    TRY(3, "尝试创建"),      // TCC Try 阶段
    CONFIRM(4, "确认创建"),   // TCC Confirm 阶段
    CANCEL(5, "取消订单"),    // TCC Cancel 阶段
    ;

    private Integer code;
    private String info;

    public static TradeOrderStatusEnumVO valueOf(Integer code) {
        switch (code) {
            case 0:
                return CREATE;
            case 1:
                return COMPLETE;
            case 2:
                return CLOSE;
            case 3:
                return TRY;
            case 4:
                return CONFIRM;
            case 5:
                return CANCEL;
        }
        return CREATE;
    }

}
