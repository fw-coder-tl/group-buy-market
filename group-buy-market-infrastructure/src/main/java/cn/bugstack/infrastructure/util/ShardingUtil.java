package cn.bugstack.infrastructure.util;

import lombok.extern.slf4j.Slf4j;

/**
 * 分片工具类（单库分表）
 * 
 * 功能：
 * 1. 计算分表索引
 * 2. 生成分片表名
 * 3. 支持按 user_id 分片
 * 
 * 分片规则：
 * - 单库（group_buy_market）
 * - 4张表（_0, _1, _2, _3）
 * - 分片算法：Math.abs(user_id.hashCode()) % 4
 * 
 * @author liang.tian
 */
@Slf4j
public class ShardingUtil {
    
    /** 分表数量 */
    private static final int TABLE_COUNT = 4;

    /**
     * 计算分表索引
     * 
     * @param userId 用户ID
     * @return 分表索引（0-3）
     */
    public static int calculateTableIndex(String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        int index = Math.abs(userId.hashCode()) % TABLE_COUNT;
        log.debug("计算分表索引: userId={}, tableIndex={}", userId, index);
        return index;
    }

    /**
     * 生成分片表名
     * 
     * @param baseTableName 基础表名（如：group_buy_order_list）
     * @param userId 用户ID
     * @return 分片表名（如：group_buy_order_list_0）
     */
    public static String generateShardingTableName(String baseTableName, String userId) {
        int tableIndex = calculateTableIndex(userId);
        String tableName = baseTableName + "_" + tableIndex;
        log.debug("生成分片表名: baseTableName={}, userId={}, tableName={}", 
                baseTableName, userId, tableName);
        return tableName;
    }

    /**
     * 生成归档表名
     * 
     * @param baseTableName 基础表名（如：group_buy_order_list）
     * @param userId 用户ID
     * @return 归档表名（如：group_buy_order_list_archive）
     * 注意：归档表不分片，使用单表
     */
    public static String generateArchiveTableName(String baseTableName, String userId) {
        // 归档表不分片，统一使用 _archive 后缀
        String tableName = baseTableName + "_archive";
        log.debug("生成归档表名: baseTableName={}, userId={}, tableName={}", 
                baseTableName, userId, tableName);
        return tableName;
    }
}

