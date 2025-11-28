package cn.bugstack.domain.trade.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 库存扣减流水记录
 * 
 * 对应 Redis Hash 中的 value（JSON 格式）
 * 示例：{"action":"decrease","from":100,"to":99,"change":1,"by":"DECREASE_userId_orderId","timestamp":1234567890}
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RedisStockLogVO {

    /** 操作类型：decrease-扣减, increase-回滚 */
    private String action;
    
    /** 扣减前库存（可能是字符串或数字） */
    private Object from;
    
    /** 扣减后库存 */
    private Long to;
    
    /** 扣减数量（可能是字符串或数字） */
    private Object change;
    
    /** 操作标识符（DECREASE_userId_orderId，可能是带引号的字符串） */
    private String by;
    
    /** 时间戳（毫秒） */
    private Long timestamp;
    
    /**
     * 获取扣减数量（处理字符串和数字类型）
     */
    public Integer getChangeAsInteger() {
        if (change == null) {
            return null;
        }
        if (change instanceof Integer) {
            return (Integer) change;
        }
        if (change instanceof String) {
            try {
                return Integer.parseInt((String) change);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 从 identifier 中提取 orderId
     * identifier 格式：DECREASE_{userId}_{orderId}
     * 注意：by 字段可能是带引号的字符串，需要去除引号
     */
    public String extractOrderId() {
        String identifier = normalizeIdentifier(by);
        if (identifier == null || !identifier.startsWith("DECREASE_")) {
            return null;
        }
        String[] parts = identifier.split("_", 3);
        if (parts.length < 3) {
            return null;
        }
        return parts[2]; // orderId
    }
    
    /**
     * 从 identifier 中提取 userId
     * identifier 格式：DECREASE_{userId}_{orderId}
     */
    public String extractUserId() {
        String identifier = normalizeIdentifier(by);
        if (identifier == null || !identifier.startsWith("DECREASE_")) {
            return null;
        }
        String[] parts = identifier.split("_", 3);
        if (parts.length < 3) {
            return null;
        }
        return parts[1]; // userId
    }
    
    /**
     * 规范化 identifier（去除引号等）
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        // 去除首尾引号
        identifier = identifier.trim();
        if (identifier.startsWith("\"") && identifier.endsWith("\"")) {
            identifier = identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }
}

