package cn.bugstack.domain.trade.service;

import cn.bugstack.domain.trade.model.entity.MarketPayOrderEntity;
import cn.bugstack.domain.trade.model.entity.PayActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.model.entity.UserEntity;

/**
 * 普通商品下单服务接口（Domain层）
 * 
 * 对标 NFTurbo 的 normalBuy
 * 
 * 特点：
 * 1. 保留拼团玩法
 * 2. 创建队伍（如果需要）
 * 3. 扣减队伍库存
 * 4. 扣减商品库存
 * 5. 支持 TCC 模式和同步模式
 * 
 * @author liang.tian
 */
public interface INormalGoodsTradeService {

    /**
     * 锁定普通商品订单（TCC模式，带拼团）
     * 
     * @param userEntity 用户实体
     * @param payActivityEntity 活动实体
     * @param payDiscountEntity 优惠实体
     * @return 订单实体
     * @throws Exception 异常
     */
    MarketPayOrderEntity lockNormalGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception;

    /**
     * 同步锁定普通商品订单（参考 NFTurbo 设计）
     * 
     * 流程：
     * 1. Redis 预扣减库存
     * 2. 同步 DB 扣减库存（带流水，保证幂等）
     * 3. 同步创建订单
     * 4. 失败时回滚 Redis 库存
     * 
     * @param userEntity 用户实体
     * @param payActivityEntity 活动实体
     * @param payDiscountEntity 优惠实体
     * @return 订单实体
     * @throws Exception 异常
     */
    MarketPayOrderEntity lockNormalGoodsOrderSync(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception;
}

