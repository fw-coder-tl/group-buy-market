package cn.bugstack.api;

import cn.bugstack.api.dto.LockMarketPayOrderRequestDTO;
import cn.bugstack.api.dto.LockMarketPayOrderResponseDTO;
import cn.bugstack.api.response.Response;

/**
 * 热点商品交易服务接口
 * 
 * 用于热点商品（秒杀商品）的下单接口
 * 特点：
 * - 不做拼团
 * - 使用热点商品下单服务（HotGoodsTradeService）
 * - 支持高并发场景
 * 
 * @author liang.tian
 */
public interface IHotGoodsTradeService {

    /**
     * 热点商品下单（锁单）
     * 
     * @param requestDTO 锁单商品信息
     * @return 锁单结果信息
     */
    Response<LockMarketPayOrderResponseDTO> lockHotGoodsOrder(LockMarketPayOrderRequestDTO requestDTO);

}

