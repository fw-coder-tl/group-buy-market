package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.NormalGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.function.Consumer;

/**
 * 普通商品订单疑似取消消息监听器（Trigger层）
 * 
 * 参考 NFTurbo 的 NormalBuyMsgListener.normalBuyPreCancel
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 处理 normalBuyPreCancel 消息（延迟消息）：
 * 1. 检查订单状态
 * 2. 如果订单已确认，直接返回
 * 3. 否则执行取消操作
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class NormalGoodsOrderPreCancelListener extends AbstractStreamConsumer {

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;

    @Bean
    Consumer<Message<MessageBody>> normalGoodsOrderPreCancel() {
        return msg -> {
            try {
                log.info("普通商品订单疑似取消消息-收到消息");
                
                // 1. 解析消息（参考 NFTurbo）
                NormalGoodsOrderAggregate aggregate = getMessage(msg, NormalGoodsOrderAggregate.class);
                String orderId = aggregate.getOrderId();
                String userId = aggregate.getUserEntity().getUserId();

                // 2. 查询订单状态
                MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
                
                // 3. 如果订单已经创建成功（状态为 CONFIRM），则直接返回
                if (order != null && TradeOrderStatusEnumVO.CONFIRM.equals(order.getTradeOrderStatusEnumVO())) {
                    log.info("普通商品订单疑似取消消息-订单已确认，无需取消: orderId={}, status={}", 
                            orderId, order.getTradeOrderStatusEnumVO());
                    return;
                }

                // 4. 订单未确认，执行取消操作
                doCancel(aggregate);

                log.info("普通商品订单疑似取消消息-处理成功: orderId={}", orderId);
            } catch (Exception e) {
                log.error("普通商品订单疑似取消消息-处理失败", e);
                throw new RuntimeException("普通商品订单疑似取消消息处理失败", e);
            }
        };
    }

    /**
     * 执行取消操作
     * 
     * 对标 NFTurbo 的 NormalBuyMsgListener.doCancel
     */
    private void doCancel(NormalGoodsOrderAggregate aggregate) {
        String orderId = aggregate.getOrderId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();
        String teamId = aggregate.getTeamId();
        Integer validTime = aggregate.getPayActivityEntity().getValidTime();

        // 1. 取消扣减库存（回滚 Redis 库存）
        boolean cancelInventoryResult = skuRepository.cancelDecreaseInventory(activityId, goodsId, 1, orderId);
        Assert.isTrue(cancelInventoryResult, "cancelDecreaseInventory failed");

        // 2. 回滚拼团库存（如果已扣减）
        rollbackTeamStockIfNeeded(activityId, teamId, validTime, orderId);

        // 3. 取消订单（将订单状态改为 CANCEL）
        boolean cancelOrderResult = tradeRepository.cancelOrder(orderId);
        Assert.isTrue(cancelOrderResult, "cancelOrder failed");

        log.info("普通商品订单疑似取消-成功: orderId={}, activityId={}, goodsId={}, teamId={}", orderId, activityId, goodsId, teamId);
    }

    /**
     * 回滚拼团库存（如果需要）
     */
    private void rollbackTeamStockIfNeeded(Long activityId, String teamId, Integer validTime, String orderId) {
        try {
            // 只有在teamId不为空时，才需要回滚拼团库存
            if (StringUtils.isNotBlank(teamId) && activityId != null && validTime != null) {
                String recoveryTeamStockKey = TradeLockRuleFilterFactory.generateRecoveryTeamStockKey(activityId, teamId);
                tradeRepository.recoveryTeamStock(recoveryTeamStockKey, validTime);
                log.info("普通商品订单疑似取消-回滚拼团库存成功: orderId={}, teamId={}, recoveryTeamStockKey={}", 
                        orderId, teamId, recoveryTeamStockKey);
            } else {
                log.debug("普通商品订单疑似取消-无需回滚拼团库存: orderId={}, teamId={}", orderId, teamId);
            }
        } catch (Exception e) {
            log.error("普通商品订单疑似取消-回滚拼团库存失败: orderId={}, teamId={}", orderId, teamId, e);
            // 不回滚失败不影响主流程，只记录日志
        }
    }
}

