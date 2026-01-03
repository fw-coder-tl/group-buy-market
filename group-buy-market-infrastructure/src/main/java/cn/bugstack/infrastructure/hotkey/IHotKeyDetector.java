package cn.bugstack.infrastructure.hotkey;

/**
 * 热点商品探测服务接口
 * 
 * 基于 JD HotKey 框架的自动热点探测能力
 * 
 * @author liang.tian
 */
public interface IHotKeyDetector {

    /**
     * 判断商品是否为热点商品
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @return true-热点商品，false-普通商品
     */
    boolean isHotGoods(Long activityId, String goodsId);
}

