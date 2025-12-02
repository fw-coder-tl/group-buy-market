package cn.bugstack.infrastructure.dao;

import cn.bugstack.infrastructure.dao.po.GroupBuyOrderList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 用户拼单明细
 * @create 2025-01-11 09:07
 */
@Mapper
public interface IGroupBuyOrderListDao {

    void insert(GroupBuyOrderList groupBuyOrderListReq);

    GroupBuyOrderList queryGroupBuyOrderRecordByOutTradeNo(GroupBuyOrderList groupBuyOrderListReq);

    /**
     * 通过订单ID查询订单记录
     * @param groupBuyOrderListReq 包含userId和orderId的查询对象
     * @return 订单记录
     */
    GroupBuyOrderList queryGroupBuyOrderRecordByOrderId(GroupBuyOrderList groupBuyOrderListReq);

    Integer queryOrderCountByActivityId(GroupBuyOrderList groupBuyOrderListReq);

    int updateOrderStatus2COMPLETE(GroupBuyOrderList groupBuyOrderListReq);

    List<String> queryGroupBuyCompleteOrderOutTradeNoListByTeamId(String teamId);

    List<GroupBuyOrderList> queryInProgressUserGroupBuyOrderDetailListByUserId(GroupBuyOrderList groupBuyOrderListReq);

    List<GroupBuyOrderList> queryInProgressUserGroupBuyOrderDetailListByRandom(GroupBuyOrderList groupBuyOrderListReq);

    List<GroupBuyOrderList> queryInProgressUserGroupBuyOrderDetailListByActivityId(Long activityId);

    int unpaid2Refund(GroupBuyOrderList groupBuyOrderListReq);

    int paid2Refund(GroupBuyOrderList groupBuyOrderListReq);

    int paidTeam2Refund(GroupBuyOrderList groupBuyOrderListReq);

    /**
     * 查询超时未支付订单列表
     * 条件：当前时间不在活动时间范围内、状态为0（初始锁定）、out_trade_time为空
     * @return 超时未支付订单列表，限制10条
     */
    List<GroupBuyOrderList> queryTimeoutUnpaidOrderList();

    /**
     * 更新订单状态（TCC 模式使用）
     * @param orderId 订单ID
     * @param status 状态（TRY=3, CONFIRM=4, CANCEL=5）
     * @return 更新行数
     */
    int updateOrderStatus(@Param("orderId") String orderId, @Param("status") Integer status);

    /**
     * 查询指定日期之前的订单（用于归档）
     * @param archiveDate 归档日期
     * @return 需要归档的订单列表
     */
    List<GroupBuyOrderList> queryOrdersBeforeDate(java.util.Date archiveDate);

    /**
     * 根据ID删除订单
     * @param id 订单ID
     */
    void deleteById(Long id);

    /**
     * 从归档表查询订单（支持冷热分离）
     * @param groupBuyOrderListReq 包含userId和orderId的查询对象
     * @return 订单记录
     */
    GroupBuyOrderList queryGroupBuyOrderRecordByOrderIdFromArchive(GroupBuyOrderList groupBuyOrderListReq);

}
