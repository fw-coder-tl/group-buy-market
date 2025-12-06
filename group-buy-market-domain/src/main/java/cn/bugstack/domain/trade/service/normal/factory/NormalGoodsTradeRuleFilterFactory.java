package cn.bugstack.domain.trade.service.normal.factory;

import cn.bugstack.domain.trade.model.entity.TradeLockRuleCommandEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.domain.trade.service.lock.filter.ActivityUsabilityRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.GoodsStockOccupyRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.TeamStockOccupyRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.UserTakeLimitRuleFilter;
import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * 普通商品交易规则过滤工厂
 * 
 * 责任链节点：
 * 1. ActivityUsabilityRuleFilter - 活动可用性过滤
 * 2. UserTakeLimitRuleFilter - 用户购买限制过滤
 * 3. GoodsStockOccupyRuleFilter - 商品库存占用过滤
 * 4. TeamStockOccupyRuleFilter - 队伍库存占用过滤（拼团）
 * 
 * @author liang.tian
 */
@Slf4j
@Service
public class NormalGoodsTradeRuleFilterFactory {

    @Bean("normalGoodsTradeRuleFilter")
    public BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> normalGoodsTradeRuleFilter(
            ActivityUsabilityRuleFilter activityUsabilityRuleFilter,
            UserTakeLimitRuleFilter userTakeLimitRuleFilter,
            GoodsStockOccupyRuleFilter goodsStockOccupyRuleFilter,
            TeamStockOccupyRuleFilter teamStockOccupyRuleFilter
    ) {
        // 组装链
        // 注意：直接使用 TradeLockRuleFilterFactory.DynamicContext
        // 因为 TeamStockOccupyRuleFilter 使用的是 TradeLockRuleFilterFactory.DynamicContext
        LinkArmory<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> linkArmory =
                new LinkArmory<>("普通商品交易规则过滤链",
                        activityUsabilityRuleFilter,
                        userTakeLimitRuleFilter,
                        goodsStockOccupyRuleFilter,
                        teamStockOccupyRuleFilter
                );

        // 链对象
        return linkArmory.getLogicLink();
    }
}

