package cn.bugstack.domain.trade.adapter.port;

/**
 * Redis适配器接口 (Domain层定义,Infrastructure层实现)
 */
public interface IRedisAdapter {

    /**
     * 队伍库存动态扣减（关键方法）
     * 特点：
     * 1. 如果队伍库存不存在，自动初始化为 1
     * 2. 如果队伍库存已满（>= targetCount），返回 -1
     * 3. 支持幂等性（同一 identifier 只能扣减一次）
     * 4. 记录扣减流水
     *
     * @param stockKey 队伍库存 Key（如：group_buy_market_team_stock_key_100123_60350079）
     * @param logKey 队伍库存流水 Key（如：group_buy_market_team_stock_log_key_100123_60350079）
     * @param identifier 扣减标识（如：DECREASE_user01_orderId123）
     * @param targetCount 队伍目标人数（如：3）
     * @return 扣减后的库存数量（-1 表示队伍已满，-2 表示重复操作）
     */
    Long decreaseTeamStockDynamically(String stockKey, String logKey, String identifier, int targetCount);
    
    /**
     * 原子扣减库存并记录流水
     */
    Long decreaseStockWithLog(String stockKey, String logKey, String identifier, int count);
    
    /**
     * 回滚库存
     */
    Long increaseStockWithLog(String stockKey, String logKey, String identifier, int count);
    
    /**
     * 获取库存扣减流水
     */
    String getStockDecreaseLog(String logKey, String identifier);
    
    /**
     * 删除库存扣减流水
     */
    void removeStockDecreaseLog(String logKey, String identifier);

    /**
     * 扫描库存流水Key
     */
    Iterable<String> scanStockLogKeys(String pattern);

    /**
     * 获取所有库存流水
     */
    java.util.Map<String, String> getAllStockDecreaseLogs(String logKey);

    /**
     * 初始化团队库存（如果已存在则不覆盖）
     * @param teamId 团队ID
     * @param stockCount 库存数量
     * @return true-初始化成功，false-已存在
     */
    boolean initTeamStock(String teamId, Integer stockCount);

    /**
     * 批量初始化团队库存
     * @param teamStockMap key=teamId, value=stockCount
     * @return 成功初始化的团队数量
     */
    int batchInitTeamStock(java.util.Map<String, Integer> teamStockMap);

    /**
     * 初始化商品库存（如果已存在则不覆盖）
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param stockCount 库存数量
     * @return true-初始化成功，false-已存在
     */
    boolean initGoodsStock(Long activityId, String goodsId, Integer stockCount);
}