package cn.bugstack.infrastructure.hotkey.detector;

import cn.bugstack.infrastructure.hotkey.IHotKeyDetector;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 热点商品探测服务实现（Infrastructure层）
 * 
 * 使用 JD HotKey 框架进行热点探测：
 * - 通过 JdHotKeyStore.isHotKey() 判断是否为热点商品
 * - HotKey 会自动进行热点探测和本地缓存
 * 
 * 热点探测逻辑：
 * - 每次调用 isHotGoods 时，HotKey 客户端会将 Key 上报给 Worker
 * - Worker 统计访问频率，如果达到阈值（如：2秒内10次），则推送给所有客户端
 * - 客户端收到推送后，将热点 Key 缓存到本地 Caffeine
 * - 后续访问直接命中本地缓存，不再查询 Redis
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotKeyDetectorImpl implements IHotKeyDetector {

    // HotKey 前缀，用于区分不同类型的热点
    private static final String HOTKEY_PREFIX = "goods__";

    /**
     * 判断商品是否为热点商品
     * 
     * HotKey 探测原理：
     * - 调用 isHotKey 时，如果是热点则返回 true
     * - 如果不是热点，返回 false，并将 key 上报给 Worker 进行频率统计
     * - 当访问频率达到规则阈值时，Worker 会推送热点通知
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @return true-热点商品，false-普通商品
     */
    @Override
    public boolean isHotGoods(Long activityId, String goodsId) {
        String hotKey = buildHotKey(activityId, goodsId);
        
        try {
            boolean isHot = JdHotKeyStore.isHotKey(hotKey);
            if (isHot) {
                log.debug("HotKey探测到热点商品: activityId={}, goodsId={}", activityId, goodsId);
            }
            return isHot;
        } catch (Exception e) {
            // HotKey 异常时，默认返回非热点，走普通流程
            log.warn("HotKey探测异常，默认非热点: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建 HotKey 探测 Key
     * 格式：goods__{activityId}_{goodsId}
     * 
     * 注意：使用双下划线作为前缀，与 HotKey Dashboard 规则配置匹配
     * 规则：以 "goods__" 开头的 key，2秒内访问超过10次即为热点
     */
    private String buildHotKey(Long activityId, String goodsId) {
        return HOTKEY_PREFIX + activityId + "_" + goodsId;
    }
}
