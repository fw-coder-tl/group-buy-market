package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.function.Consumer;

/**
 * 热点商品订单疑似取消消息监听器（Trigger层）
 * 
 * 参考 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusPreCancel
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 处理 hotGoodsOrderPreCancel 消息（延迟消息）：
 * 1. 检查订单状态
 * 2. 如果订单已创建成功（状态为 CREATE），说明之前查询失败（网络延迟或数据库异常），做补偿处理
 * 3. 如果订单不存在，回滚库存
 * 
 * 设计思路：
 * - 解决 OrderCreateTransactionListener 中因为网络延迟或数据库异常而导致查询到的订单状态不是 CREATE，
 *   但是后来又变成了 CREATE 的情况
 * - 延迟检查，给订单创建足够的时间
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotGoodsOrderPreCancelListener extends AbstractStreamConsumer {

    @Resource
    private ISkuRepository skuRepository;

    @Resource
    private ITradeRepository tradeRepository;

    @Bean
    Consumer<Message<MessageBody>> hotGoodsOrderPreCancel() {
        return msg -> {
            try {
                log.warn("热点商品订单疑似取消消息-收到消息");
                
                // 1. 解析消息（参考 NFTurbo）
                HotGoodsOrderAggregate aggregate = getMessage(msg, HotGoodsOrderAggregate.class);
                String orderId = aggregate.getOrderId();
                String userId = aggregate.getUserEntity().getUserId();

                // 2. 查询订单状态
                MarketPayOrderEntity order = tradeRepository.queryMarketPayOrderEntityByOrderId(userId, orderId);
                
                // 3. 如果订单已经创建成功（状态为 CREATE），说明之前查询失败（网络延迟或数据库异常）
                if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                    log.info("热点商品订单疑似取消消息-订单已创建成功，之前查询失败（网络延迟或数据库异常），做补偿处理: orderId={}, status={}", 
                            orderId, order.getTradeOrderStatusEnumVO());
                    
                    // ⚠️ 注意：热点商品不做拼团，所以不需要更新商品销量等补偿操作
                    // 如果后续需要补偿操作（如更新商品销量），可以在这里添加
                    // 参考 NFTurbo: goodsFacadeService.saleWithoutHint(goodsSaleRequest);
                    
                    return;
                }

                // 4. 订单不存在，回滚库存
                log.warn("热点商品订单疑似取消消息-订单不存在，回滚库存: orderId={}", orderId);
                doCancel(aggregate);

                log.info("热点商品订单疑似取消消息-处理成功: orderId={}", orderId);
            } catch (Exception e) {
                log.error("热点商品订单疑似取消消息-处理失败", e);
                throw new RuntimeException("热点商品订单疑似取消消息处理失败", e);
            }
        };
    }

    /**
     * 执行取消操作（回滚库存）
     * 
     * 对标 NFTurbo 的 NewBuyPlusMsgListener.newBuyPlusPreCancel 中的库存回滚逻辑
     * 
     * 注意：这里只回滚 Redis 库存，因为订单创建失败时，数据库库存可能已经回滚了
     */
    private void doCancel(HotGoodsOrderAggregate aggregate) {
        String orderId = aggregate.getOrderId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

        // 回滚 Redis 库存（热点商品只回滚商品库存，不做队伍库存回滚）
        // ⚠️ 注意：这里使用 cancelDecreaseInventory，它会回滚 Redis 库存
        boolean cancelResult = skuRepository.cancelDecreaseInventory(activityId, goodsId, 1, orderId);
        Assert.isTrue(cancelResult, "cancelDecreaseInventory failed");

        log.info("热点商品订单疑似取消-库存回滚成功: orderId={}, activityId={}, goodsId={}", orderId, activityId, goodsId);
    }
}

