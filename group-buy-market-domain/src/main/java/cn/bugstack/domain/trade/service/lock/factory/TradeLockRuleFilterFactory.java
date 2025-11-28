package cn.bugstack.domain.trade.service.lock.factory;

import cn.bugstack.domain.trade.model.entity.GroupBuyActivityEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleCommandEntity;
import cn.bugstack.domain.trade.model.entity.TradeLockRuleFilterBackEntity;
import cn.bugstack.domain.trade.service.lock.filter.ActivityUsabilityRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.GoodsStockOccupyRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.TeamStockOccupyRuleFilter;
import cn.bugstack.domain.trade.service.lock.filter.UserTakeLimitRuleFilter;
import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 交易规则过滤工厂
 * @create 2025-01-25 08:41
 */
@Slf4j
@Service
public class TradeLockRuleFilterFactory {

    // 组队库存redis的前缀
    private static final String teamStockKey = "group_buy_market_team_stock_key_";
    // 商品库存redis的前缀
    private static final String goodsStockKey = "group_buy_market_goods_stock_key_";
    // 商品库存流水的前缀
    private static final String goodsStockLogKey = "group_buy_market_goods_stock_log_key_";

    @Bean("tradeRuleFilter")
    public BusinessLinkedList<TradeLockRuleCommandEntity, DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter(
            ActivityUsabilityRuleFilter activityUsabilityRuleFilter,
            UserTakeLimitRuleFilter userTakeLimitRuleFilter
            //TeamStockOccupyRuleFilter teamStockOccupyRuleFilter,
            //GoodsStockOccupyRuleFilter goodsStockOccupyRuleFilter
    ) {

        // 组装链
        LinkArmory<TradeLockRuleCommandEntity, DynamicContext, TradeLockRuleFilterBackEntity> linkArmory =
                new LinkArmory<>("交易规则过滤链",
                        activityUsabilityRuleFilter,
                        userTakeLimitRuleFilter
                        //teamStockOccupyRuleFilter,
                        //goodsStockOccupyRuleFilter
                );

        // 链对象
        return linkArmory.getLogicLink();
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        private GroupBuyActivityEntity groupBuyActivity;

        private Integer userTakeOrderCount;

        private String goodsId;

        private String recoveryTeamStockKey;

        public String generateTeamStockKey(String teamId) {
            if (StringUtils.isBlank(teamId)) return null;
            return TradeLockRuleFilterFactory.generateTeamStockKey(groupBuyActivity.getActivityId(), teamId);
        }

        public String generateRecoveryTeamStockKey(String teamId) {
            if (StringUtils.isBlank(teamId)) return null;
            return TradeLockRuleFilterFactory.generateRecoveryTeamStockKey(groupBuyActivity.getActivityId(), teamId);
        }

        public String generateGoodsStockKey(String goodsId){
            if(StringUtils.isBlank(goodsId)) return null;
            return TradeLockRuleFilterFactory.generateGoodsStockKey(groupBuyActivity.getActivityId(),goodsId);
        }

        public String generateGoodsSockKeyLogKey(String goodsId){
            if(StringUtils.isBlank(goodsId)) return null;
            return TradeLockRuleFilterFactory.generateGoodsStockLogKey(groupBuyActivity.getActivityId(),goodsId);
        }

    }

    // 组队库存键值对 key：group_buy_market_team_stock_key_{activityId}_{teamId} value：组队库存数量
    public static String generateTeamStockKey(Long activityId, String teamId){
        return teamStockKey + activityId + "_" + teamId;
    }

    // 组队库存恢复键值对 key：group_buy_market_team_stock_key_{activityId}_{teamId}_recovery value：组队库存恢复数量
    public static String generateRecoveryTeamStockKey(Long activityId, String teamId) {
        return teamStockKey + activityId + "_" + teamId + "_recovery";
    }

    // 商品库存键值对 key：group_buy_market_goods_stock_key_{activityId}_{goodsId} value：商品库存数量
    public static String generateGoodsStockKey(Long activityId, String goodsId){
        return goodsStockKey + "_" + activityId + "_" + goodsId;
    }

    // 库存扣减流水redis前缀
    public static String generateGoodsStockLogKey(Long activityId,String goodsId) {
        return goodsStockLogKey + "_" +activityId + "_" + goodsId;
    }

}
