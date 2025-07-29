package cn.bugstack.domain.trade.service;

import cn.bugstack.domain.trade.model.entity.TradeRefundBehaviorEntity;
import cn.bugstack.domain.trade.model.entity.TradeRefundCommandEntity;
import cn.bugstack.domain.trade.model.valobj.TeamRefundSuccess;

/**
 * 退单，逆向流程接口
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/7/8 07:24
 */
public interface ITradeRefundOrderService {

    TradeRefundBehaviorEntity refundOrder(TradeRefundCommandEntity tradeRefundCommandEntity) throws Exception;

    /**
     * 退单恢复锁单库存
     * @param teamRefundSuccess 退单消息
     * @throws Exception 异常
     */
    void restoreTeamLockStock(TeamRefundSuccess teamRefundSuccess) throws Exception;

}
