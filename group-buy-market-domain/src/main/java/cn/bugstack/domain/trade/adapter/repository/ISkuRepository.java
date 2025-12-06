package cn.bugstack.domain.trade.adapter.repository;

public interface ISkuRepository {
    
    /**
     * 扣减商品库存（乐观锁）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 扣减数量
     * @param orderId 订单ID（用于幂等性检查）
     * @param userId 用户ID（用于记录流水）
     * @return 是否扣减成功
     */
    boolean decreaseSkuStock(Long activityId, String goodsId, Integer quantity, String orderId, String userId);
    
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

    /**
     * TCC Try：尝试扣减库存（Redis 预扣减，不扣减数据库）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 扣减数量
     * @param orderId 订单ID（用于幂等性检查）
     * @param userId 用户ID（用于记录流水）
     * @return 是否成功
     */
    boolean tryDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId, String userId);

    /**
     * TCC Confirm：确认扣减库存（真正扣减数据库库存）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 扣减数量
     * @param orderId 订单ID
     * @return 是否成功
     */
    boolean confirmDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId);

    /**
     * TCC Cancel：取消扣减库存（回滚 Redis 库存）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 回滚数量
     * @param orderId 订单ID
     * @return 是否成功
     */
    boolean cancelDecreaseInventory(Long activityId, String goodsId, Integer quantity, String orderId);

    /**
     * 原子操作：在同一个事务中扣减商品库存和增加队伍人数
     * 
     * 对标 newBuyPlus：在本地事务中同步执行数据库操作
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param quantity 商品扣减数量
     * @param teamId 队伍ID（可为null，表示不需要增加队伍人数）
     * @param orderId 订单ID（用于幂等性检查）
     * @param userId 用户ID（用于记录流水）
     * @return 是否成功
     */
    boolean decreaseSkuStockAndIncreaseTeamCount(Long activityId, String goodsId, Integer quantity, 
                                                  String teamId, String orderId, String userId);
}