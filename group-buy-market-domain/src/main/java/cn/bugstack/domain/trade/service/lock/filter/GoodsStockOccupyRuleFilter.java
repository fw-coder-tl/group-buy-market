package cn.bugstack.domain.trade.service.lock.filter;

import cn.bugstack.domain.activity.adapter.repository.IActivityRepository;
import cn.bugstack.domain.activity.model.valobj.SkuVO;
import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.GroupBuyActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleCommandEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.types.enums.ResponseCode;
import cn.bugstack.types.exception.AppException;
import cn.bugstack.types.utils.SnowflakeIdUtil;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;


@Service
@Slf4j
public class GoodsStockOccupyRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private IActivityRepository activityRepository;

    @Resource
    private IMessageProducer messageProducer;

    @Resource
    private IRedisAdapter redisAdapter;

    private static final String ORDER_CREATE_BINDING = "orderCreate-out-0";

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity tradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-商品库存校验 userId:{} activityId:{}",
                tradeLockRuleCommandEntity.getUserId(),
                tradeLockRuleCommandEntity.getActivityId());

//        // 生成订单号（雪花算法）
//        String orderId = SnowflakeIdUtil.nextIdStr();

//        // 获取商品信息
//        SkuVO skuVO = activityRepository.querySkuByGoodsId(tradeLockRuleCommandEntity.getGoodsId());
//
//        // 构建identifier
//        String identifier = buildIdentifier(tradeLockRuleCommandEntity.getUserId(), orderId);

        // 扣减商品库存（在规则过滤阶段预扣，如果失败则直接返回）
//        Long result = redisAdapter.decreaseStockWithLog(
//                dynamicContext.generateGoodsStockKey(tradeLockRuleCommandEntity.getGoodsId()),         // 商品键值对
//                dynamicContext.generateGoodsSockKeyLogKey(tradeLockRuleCommandEntity.getGoodsId()),    // 库存扣减流水键值对
//                identifier,
//                1);
//
//        if (result == null || result < 0) {
//            log.warn("交易规则过滤-商品库存校验失败 userId:{} activityId:{} goodsId:{} orderId:{}",
//                    tradeLockRuleCommandEntity.getUserId(), dynamicContext.getGroupBuyActivity().getActivityId(), dynamicContext.getGoodsId(), orderId);
//            throw new AppException(ResponseCode.E0009); // 商品库存不足
//        }

        // 构建聚合对象
//        GroupBuyOrderAggregate groupBuyOrderAggregate = GroupBuyOrderAggregate.builder()
//                .userEntity(userEntity)
//                .payActivityEntity(payActivityEntity)
//                .payDiscountEntity(payDiscountEntity)
//                .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
//                .orderId(orderId)
//                .build();
//
//        // 发送RocketMQ事务消息 (本地事务中预扣减库存)
//        boolean sendResult = messageProducer.sendOrderCreateMessage(
//                ORDER_CREATE_BINDING,   // 创建订单绑定
//                orderId,  // 使用orderId作为消息key
//                JSON.toJSONString(groupBuyOrderAggregate),
//                groupBuyOrderAggregate
//        );
//
//        log.info("交易规则过滤-商品库存校验成功 userId:{} activityId:{} goodsId:{} orderId:{} 剩余库存:{}",
//                tradeLockRuleCommandEntity.getUserId(), dynamicContext.getGroupBuyActivity().getActivityId(), dynamicContext.getGoodsId(), orderId, result);

        return TradeLockRuleFilterBackEntity.builder()
                .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                .recoveryTeamStockKey(dynamicContext.getRecoveryTeamStockKey())
                .build();
    }

    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}
