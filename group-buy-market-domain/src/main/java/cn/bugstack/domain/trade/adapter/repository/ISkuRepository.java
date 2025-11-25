package cn.bugstack.domain.trade.adapter.repository;

public interface ISkuRepository {
    
    /**
     * 扣减商品库存（乐观锁）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 扣减数量
     * @return 是否扣减成功
     */
    boolean decreaseSkuStock(Long activityId, String goodsId, Integer quantity);
    
    /**
     * 支付成功：冻结库存转为已售
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 数量
     * @return 是否成功
     */
    boolean confirmSkuStock(Long activityId, String goodsId, Integer quantity);
    
    /**
     * 取消订单：释放冻结库存
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 数量
     * @return 是否成功
     */
    boolean releaseSkuStock(Long activityId, String goodsId, Integer quantity);
}