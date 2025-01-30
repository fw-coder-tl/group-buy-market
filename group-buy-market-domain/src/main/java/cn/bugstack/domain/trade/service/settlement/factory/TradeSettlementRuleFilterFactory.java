package cn.bugstack.domain.trade.service.settlement.factory;

import cn.bugstack.domain.trade.model.entity.GroupBuyTeamEntity;
import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.entity.TradeSettlementRuleCommandEntity;
import cn.bugstack.domain.trade.model.entity.TradeSettlementRuleFilterBackEntity;
import cn.bugstack.domain.trade.service.settlement.filter.EndRuleFilter;
import cn.bugstack.domain.trade.service.settlement.filter.OutTradeNoRuleFilter;
import cn.bugstack.domain.trade.service.settlement.filter.SCRuleFilter;
import cn.bugstack.domain.trade.service.settlement.filter.SettableRuleFilter;
import cn.bugstack.types.design.framework.link.model2.LinkArmory;
import cn.bugstack.types.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易结算规则过滤工厂
 * @create 2025-01-29 09:17
 */
@Slf4j
@Service
public class TradeSettlementRuleFilterFactory {

    @Bean("tradeSettlementRuleFilter")
    public BusinessLinkedList<TradeSettlementRuleCommandEntity,
            TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> tradeSettlementRuleFilter(
            SCRuleFilter scRuleFilter,
            OutTradeNoRuleFilter outTradeNoRuleFilter,
            SettableRuleFilter settableRuleFilter,
            EndRuleFilter endRuleFilter) {

        // 组装链
        LinkArmory<TradeSettlementRuleCommandEntity, TradeSettlementRuleFilterFactory.DynamicContext, TradeSettlementRuleFilterBackEntity> linkArmory =
                new LinkArmory<>("交易结算规则过滤链", scRuleFilter, outTradeNoRuleFilter, settableRuleFilter, endRuleFilter);

        // 链对象
        return linkArmory.getLogicLink();
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {
        // 订单营销实体对象
        private MarketPayOrderEntity marketPayOrderEntity;
        // 拼团组队实体对象
        private GroupBuyTeamEntity groupBuyTeamEntity;
    }

}
