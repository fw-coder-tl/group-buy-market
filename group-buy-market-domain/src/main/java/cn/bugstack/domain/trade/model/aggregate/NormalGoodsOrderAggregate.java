package cn.bugstack.domain.trade.model.aggregate;

import cn.bugstack.domain.trade.model.entity.PayActivityEntity;
import cn.bugstack.domain.trade.model.entity.PayDiscountEntity;
import cn.bugstack.domain.trade.model.entity.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 普通商品订单聚合对象
 * 
 * 对标 NFTurbo 的 OrderCreateAndConfirmRequest（普通商品场景）
 * 
 * 特点：
 * 1. 包含拼团相关字段（teamId、targetCount）
 * 2. 支持创建队伍
 * 3. 支持扣减队伍库存
 * 4. 用于普通商品下单场景（TCC 模式）
 * 
 * @author liang.tian
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NormalGoodsOrderAggregate {

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
    
    /** 队伍ID（拼团相关） */
    private String teamId;
    
    /** 队伍目标人数（拼团相关） */
    private Integer targetCount;
    
    /** Redis 库存是否已扣减成功（用于决定是否使用强制更新数据库） */
    private Boolean redisStockDecreased;
    
    /** Redis 扣减后的队伍当前人数（用于验证是否超过目标人数） */
    private Long redisTeamCurrentCount;
}

