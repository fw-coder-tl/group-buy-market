package cn.bugstack.domain.trade.service;

import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.entity.PayActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.model.entity.UserEntity;

/**
 * 热点商品下单服务接口（Domain层）
 * 
 * 对标 NFTurbo 的 newBuyPlus
 * 
 * 特点：
 * 1. 不做拼团组队
 * 2. 不创建队伍
 * 3. 不扣减队伍库存
 * 4. 只扣减商品库存
 * 5. 高并发场景
 * 
 * @author liang.tian
 */
public interface IHotGoodsTradeService {

    /**
     * 锁定热点商品订单
     * 
     * @param userEntity 用户实体
     * @param payActivityEntity 活动实体
     * @param payDiscountEntity 优惠实体
     * @return 订单实体
     * @throws Exception 异常
     */
    MarketPayOrderEntity lockHotGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception;
}

