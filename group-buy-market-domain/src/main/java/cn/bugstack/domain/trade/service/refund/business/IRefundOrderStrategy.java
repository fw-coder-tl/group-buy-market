package cn.bugstack.domain.trade.service.refund.business;

import cn.bugstack.domain.trade.model.entity.TradeRefundOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TeamRefundSuccess;

/**
 * 退单策略接口
 * 未支付，Unpaid
 * 未成团，UnformedTeam
 * 已成团，AlreadyFormedTeam
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/8 07:37
 */
public interface IRefundOrderStrategy {

    void refundOrder(TradeRefundOrderEntity tradeRefundOrderEntity) throws Exception;

    void reverseStock(TeamRefundSuccess teamRefundSuccess) throws Exception;

}
