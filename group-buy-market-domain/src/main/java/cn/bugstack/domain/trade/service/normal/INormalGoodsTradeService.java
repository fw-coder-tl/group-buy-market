package cn.bugstack.domain.trade.service.normal;

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
 * 5. 使用 TCC 模式（Try-Confirm-Cancel）
 * 
 * @author liang.tian
 */
public interface INormalGoodsTradeService {

    /**
     * 锁定普通商品订单（带拼团）
     * 
     * @param userEntity 用户实体
     * @param payActivityEntity 活动实体
     * @param payDiscountEntity 优惠实体
     * @return 订单实体
     * @throws Exception 异常
     */
    MarketPayOrderEntity lockNormalGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception;
}

