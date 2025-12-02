package cn.bugstack.domain.trade.service.detector;

/**
 * 热点商品探测服务接口（Domain层）
 * 
 * 参考 NFTurbo 的 HotGoodsService
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

    /**
     * 添加热点商品
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     */
    void addHotGoods(Long activityId, String goodsId);

    /**
     * 移除热点商品
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     */
    void removeHotGoods(Long activityId, String goodsId);
}

