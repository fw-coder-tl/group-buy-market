package cn.bugstack.infrastructure.adapter.detector;

import cn.bugstack.domain.trade.service.detector.IHotKeyDetector;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 热点商品探测服务实现（Infrastructure层）
 * 
 * 参考 NFTurbo 的 HotGoodsService
 * 
 * 实现方式：
 * 1. 使用 Redis Set 存储热点商品列表
 * 2. 使用本地缓存（Caffeine）加速查询
 * 3. 热点商品 Key 格式：goods:hot:{activityId}:{goodsId}
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotKeyDetectorImpl implements IHotKeyDetector {

    private static final String HOT_GOODS_SET_KEY = "group_buy_market:hot:goods:set";
    private static final String HOT_GOODS_KEY_PREFIX = "group_buy_market:hot:goods:";

    @Resource
    private RedissonClient redissonClient;

    /**
     * 本地缓存（Caffeine），加速热点商品查询
     * 过期时间：24小时
     * 最大容量：3000
     */
    private Cache<String, Boolean> hotGoodsLocalCache;

    @PostConstruct
    public void init() {
        hotGoodsLocalCache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(3000)
                .build();
    }

    @Override
    public boolean isHotGoods(Long activityId, String goodsId) {
        String hotGoodsKey = buildHotGoodsKey(activityId, goodsId);
        
        // 1. 先查本地缓存
        Boolean isHot = hotGoodsLocalCache.getIfPresent(hotGoodsKey);
        if (isHot != null) {
            return isHot;
        }
        
        // 2. 查 Redis Set
        RSet<String> hotGoodsSet = redissonClient.getSet(HOT_GOODS_SET_KEY);
        isHot = hotGoodsSet.contains(hotGoodsKey);
        
        // 3. 如果命中，写入本地缓存
        if (isHot) {
            hotGoodsLocalCache.put(hotGoodsKey, true);
        }
        
        return isHot != null && isHot;
    }

    @Override
    public void addHotGoods(Long activityId, String goodsId) {
        String hotGoodsKey = buildHotGoodsKey(activityId, goodsId);
        
        // 1. 写入本地缓存
        hotGoodsLocalCache.put(hotGoodsKey, true);
        
        // 2. 写入 Redis Set
        RSet<String> hotGoodsSet = redissonClient.getSet(HOT_GOODS_SET_KEY);
        hotGoodsSet.add(hotGoodsKey);
        
        log.info("添加热点商品: activityId={}, goodsId={}", activityId, goodsId);
    }

    @Override
    public void removeHotGoods(Long activityId, String goodsId) {
        String hotGoodsKey = buildHotGoodsKey(activityId, goodsId);
        
        // 1. 删除本地缓存
        hotGoodsLocalCache.invalidate(hotGoodsKey);
        
        // 2. 删除 Redis Set
        RSet<String> hotGoodsSet = redissonClient.getSet(HOT_GOODS_SET_KEY);
        hotGoodsSet.remove(hotGoodsKey);
        
        log.info("移除热点商品: activityId={}, goodsId={}", activityId, goodsId);
    }

    /**
     * 构建热点商品 Key
     * 格式：group_buy_market:hot:goods:{activityId}:{goodsId}
     */
    private String buildHotGoodsKey(Long activityId, String goodsId) {
        return HOT_GOODS_KEY_PREFIX + activityId + ":" + goodsId;
    }
}

