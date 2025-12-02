package cn.bugstack.domain.trade.model.aggregate;

import cn.bugstack.domain.trade.model.entity.PayActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.model.entity.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热点商品订单聚合对象
 * 
 * 对标 NFTurbo 的 OrderCreateAndConfirmRequest（热点商品场景）
 * 
 * 特点：
 * 1. 不包含拼团相关字段（teamId、targetCount）
 * 2. 只包含商品库存扣减相关字段
 * 3. 用于热点商品下单场景
 * 
 * @author liang.tian
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotGoodsOrderAggregate {

    /** 用户实体对象 */
    private UserEntity userEntity;
    
    /** 支付活动实体对象 */
    private PayActivityEntity payActivityEntity;
    
    /** 支付优惠实体对象 */
    private PayDiscountEntity payDiscountEntity;
    
    /** 已参与拼团量（用于构建数据库唯一索引） */
    private Integer userTakeOrderCount;
    
    /** 订单ID（使用雪花算法生成） */
    private String orderId;
    
    // ⚠️ 不包含以下字段（热点商品不做拼团）：
    // - teamId（队伍ID）
    // - targetCount（队伍目标人数）
}

