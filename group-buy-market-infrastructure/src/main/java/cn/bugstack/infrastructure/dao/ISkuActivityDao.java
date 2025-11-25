package cn.bugstack.infrastructure.dao;

import cn.bugstack.infrastructure.dao.po.SkuActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ISkuActivityDao {
    
    /**
     * 扣减可售库存（下单时冻结）
     * 使用乐观锁：WHERE saleable_inventory - frozen_inventory >= quantity AND lock_version = oldVersion
     */
    int decreaseSkuStock(@Param("activityId") Long activityId,
                         @Param("goodsId") String goodsId, 
                         @Param("quantity") Integer quantity,
                         @Param("oldVersion") Integer oldVersion);
    
    /**
     * 支付成功：冻结库存转为已售（减少可售库存和冻结库存）
     */
    int confirmSkuStock(@Param("activityId") Long activityId, 
                        @Param("goodsId") String goodsId, 
                        @Param("quantity") Integer quantity);
    
    /**
     * 取消订单：释放冻结库存
     */
    int releaseSkuStock(@Param("activityId") Long activityId, 
                        @Param("goodsId") String goodsId, 
                        @Param("quantity") Integer quantity);
    
    /**
     * 查询SKU库存信息
     */
    SkuActivity querySkuActivity(@Param("activityId") Long activityId,
                                 @Param("goodsId") String goodsId);
}