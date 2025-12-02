package cn.bugstack.domain.trade.adapter.repository;

import cn.bugstack.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.aggregate.NormalGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyRefundAggregate;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyTeamSettlementAggregate;
import cn.bugstack.domain.trade.model.entity.GroupBuyActivityEntity;
import cn.bugstack.domain.trade.model.entity.GroupBuyTeamEntity;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.entity.NotifyTaskEntity;
import cn.bugstack.domain.trade.model.valobj.GroupBuyProgressVO;

import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易仓储服务接口
 * @create 2025-01-11 09:07
 */
public interface ITradeRepository {

    MarketPayOrderEntity queryMarketPayOrderEntityByOutTradeNo(String userId, String outTradeNo);

    /**
     * 通过订单ID查询订单实体
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 订单实体
     */
    MarketPayOrderEntity queryMarketPayOrderEntityByOrderId(String userId, String orderId);

    MarketPayOrderEntity lockMarketPayOrder(GroupBuyOrderAggregate groupBuyOrderAggregate);

    /**
     * 锁定热点商品订单（不做拼团）
     * 
     * @param hotGoodsOrderAggregate 热点商品订单聚合对象
     * @return 订单实体
     */
    MarketPayOrderEntity lockHotGoodsOrder(HotGoodsOrderAggregate hotGoodsOrderAggregate);

    /**
     * 锁定普通商品订单（带拼团）
     * 
     * @param normalGoodsOrderAggregate 普通商品订单聚合对象
     * @return 订单实体
     */
    MarketPayOrderEntity lockNormalGoodsOrder(NormalGoodsOrderAggregate normalGoodsOrderAggregate);

    /**
     * TCC Try：尝试创建订单（创建订单但状态是 TRY）
     * @param normalGoodsOrderAggregate 普通商品订单聚合对象
     * @return 订单实体
     */
    MarketPayOrderEntity tryOrder(NormalGoodsOrderAggregate normalGoodsOrderAggregate);

    /**
     * TCC Confirm：确认订单（将订单状态从 TRY 改为 CONFIRM）
     * @param orderId 订单ID
     * @return 是否成功
     */
    boolean confirmOrder(String orderId);

    /**
     * TCC Cancel：取消订单（将订单状态改为 CANCEL）
     * @param orderId 订单ID
     * @return 是否成功
     */
    boolean cancelOrder(String orderId);

    GroupBuyProgressVO queryGroupBuyProgress(String teamId);

    GroupBuyActivityEntity queryGroupBuyActivityEntityByActivityId(Long activityId);

    Integer queryOrderCountByActivityId(Long activityId, String userId);

    GroupBuyTeamEntity queryGroupBuyTeamByTeamId(String teamId);

    NotifyTaskEntity settlementMarketPayOrder(GroupBuyTeamSettlementAggregate groupBuyTeamSettlementAggregate);

    boolean isSCBlackIntercept(String source, String channel);

    List<NotifyTaskEntity> queryUnExecutedNotifyTaskList();

    List<NotifyTaskEntity> queryUnExecutedNotifyTaskList(String teamId);

    int updateNotifyTaskStatusSuccess(NotifyTaskEntity notifyTaskEntity);

    int updateNotifyTaskStatusError(NotifyTaskEntity notifyTaskEntity);

    int updateNotifyTaskStatusRetry(NotifyTaskEntity notifyTaskEntity);

    boolean occupyTeamStock(String teamStockKey, String recoveryTeamStockKey, Integer target, Integer validTime);

    void recoveryTeamStock(String recoveryTeamStockKey, Integer validTime);

    NotifyTaskEntity unpaid2Refund(GroupBuyRefundAggregate groupBuyRefundAggregate);

    NotifyTaskEntity paid2Refund(GroupBuyRefundAggregate groupBuyRefundAggregate);

    NotifyTaskEntity paidTeam2Refund(GroupBuyRefundAggregate groupBuyRefundAggregate);

//    void refund2AddRecovery(String recoveryTeamStockKey, String orderId);

    List<UserGroupBuyOrderDetailEntity> queryTimeoutUnpaidOrderList();

    /**
     * 退单恢复队伍库存
     */
    void refund2AddRecovery(String recoveryTeamStockKey, String orderId);

    /**
     * 退单恢复商品库存（新增）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param orderId 订单ID
     * @param quantity 恢复数量
     */
    void refund2ReleaseSkuStock(Long activityId, String goodsId, String orderId, Integer quantity);

}
