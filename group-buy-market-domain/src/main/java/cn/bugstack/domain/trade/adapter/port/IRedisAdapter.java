package cn.bugstack.domain.trade.adapter.port;

/**
 * Redis适配器接口 (Domain层定义,Infrastructure层实现)
 */
public interface IRedisAdapter {
    
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
}