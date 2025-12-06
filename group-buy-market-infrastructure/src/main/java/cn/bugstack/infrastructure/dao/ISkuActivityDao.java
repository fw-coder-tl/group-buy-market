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
     * TCC Try：冻结库存（增加冻结库存，不减少可售库存）
     * 
     * 对标 NFTurbo 的 freezeInventory
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 冻结数量
     * @param oldVersion 乐观锁版本号
     * @return 更新行数
     */
    int freezeInventory(@Param("activityId") Long activityId,
                        @Param("goodsId") String goodsId, 
                        @Param("quantity") Integer quantity,
                        @Param("oldVersion") Integer oldVersion);
    
    /**
     * 查询SKU库存信息
     */
    SkuActivity querySkuActivity(@Param("activityId") Long activityId,
                                 @Param("goodsId") String goodsId);
}