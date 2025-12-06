package cn.bugstack.api;

import cn.bugstack.api.dto.LockMarketPayOrderRequestDTO;
import cn.bugstack.api.dto.LockMarketPayOrderResponseDTO;
import cn.bugstack.api.response.Response;

/**
 * 普通商品交易服务接口
 * 
 * 用于普通商品（拼团商品）的下单接口
 * 特点：
 * - 支持拼团
 * - 使用普通商品下单服务（NormalGoodsTradeService）
 * - 支持组队功能
 * 
 * @author liang.tian
 */
public interface INormalGoodsTradeService {

    /**
     * 普通商品下单（锁单）
     * 
     * @param requestDTO 锁单商品信息（可包含 teamId 用于拼团）
     * @return 锁单结果信息
     */
    Response<LockMarketPayOrderResponseDTO> lockNormalGoodsOrder(LockMarketPayOrderRequestDTO requestDTO);

}

