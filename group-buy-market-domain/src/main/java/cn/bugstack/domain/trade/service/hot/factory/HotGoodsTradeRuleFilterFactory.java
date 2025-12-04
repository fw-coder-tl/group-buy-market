package cn.bugstack.domain.trade.service.hot.factory;

import cn.bugstack.domain.trade.model.entity.TradeLockRuleCommandEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.domain.trade.service.lock.filter.ActivityUsabilityRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.GoodsStockOccupyRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.UserTakeLimitRuleFilter;
import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * 热点商品交易规则过滤工厂
 * 
 * 责任链节点：
 * 1. ActivityUsabilityRuleFilter - 活动可用性过滤
 * 2. UserTakeLimitRuleFilter - 用户购买限制过滤
 * 3. GoodsStockOccupyRuleFilter - 商品库存占用过滤
 * 
 * ⚠️ 不包含：
 * - TeamStockOccupyRuleFilter（队伍库存过滤）- 热点商品不做拼团
 * 
 * @author liang.tian
 */
@Slf4j
@Service
public class HotGoodsTradeRuleFilterFactory {

    @Bean("hotGoodsTradeRuleFilter")
    public BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> hotGoodsTradeRuleFilter(
            ActivityUsabilityRuleFilter activityUsabilityRuleFilter,
            UserTakeLimitRuleFilter userTakeLimitRuleFilter,
            GoodsStockOccupyRuleFilter goodsStockOccupyRuleFilter
    ) {
        // 组装链
        LinkArmory<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> linkArmory = new LinkArmory<>("热点商品交易规则过滤",
                activityUsabilityRuleFilter,
                userTakeLimitRuleFilter,
                goodsStockOccupyRuleFilter);

        // 组装链
        LinkArmory<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> linkArmory1 =
                new LinkArmory<>("交易规则过滤链",
                        activityUsabilityRuleFilter,
                        userTakeLimitRuleFilter
                        //teamStockOccupyRuleFilter,
                        //goodsStockOccupyRuleFilter
                );

        // 链对象
        return linkArmory.getLogicLink();
    }
}

