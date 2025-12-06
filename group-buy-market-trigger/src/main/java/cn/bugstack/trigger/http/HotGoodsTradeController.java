package cn.bugstack.trigger.http;

import cn.bugstack.api.IHotGoodsTradeService;
import cn.bugstack.api.dto.LockMarketPayOrderRequestDTO;
import cn.bugstack.api.dto.LockMarketPayOrderResponseDTO;
import cn.bugstack.api.response.Response;
import cn.bugstack.domain.activity.model.entity.MarketProductEntity;
import cn.bugstack.domain.activity.model.entity.TrialBalanceEntity;
import cn.bugstack.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import cn.bugstack.domain.activity.service.IIndexGroupBuyMarketService;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.model.valobj.NotifyConfigVO;
import cn.bugstack.domain.trade.model.valobj.NotifyTypeEnumVO;
import cn.bugstack.types.enums.ResponseCode;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;

/**
 * 热点商品交易控制器
 * 
 * 用于热点商品（秒杀商品）的下单接口
 * 特点：
 * - 不做拼团（teamId 会被忽略或设置为虚拟 teamId）
 * - 使用热点商品下单服务（HotGoodsTradeService）
 * - 支持高并发场景
 * 
 * API 路径：/api/v1/gbm/trade/hot-goods/lock_order
 * 
 * @author liang.tian
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/trade/hot-goods/")
public class HotGoodsTradeController implements IHotGoodsTradeService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    
    @Resource
    private cn.bugstack.domain.trade.service.IHotGoodsTradeService hotGoodsTradeService;

    /**
     * 热点商品下单（锁单）
     * 
     * @param requestDTO 锁单商品信息
     * @return 锁单结果信息
     */
    @RequestMapping(value = "lock_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockHotGoodsOrder(@Valid @RequestBody LockMarketPayOrderRequestDTO requestDTO) {
        try {
            // 参数校验
            String userId = requestDTO.getUserId();
            String source = requestDTO.getSource();
            String channel = requestDTO.getChannel();
            String goodsId = requestDTO.getGoodsId();
            Long activityId = requestDTO.getActivityId();
            String outTradeNo = requestDTO.getOutTradeNo();
            LockMarketPayOrderRequestDTO.NotifyConfigVO notifyConfigVO = requestDTO.getNotifyConfigVO();

            log.info("热点商品下单:{} LockMarketPayOrderRequestDTO:{}", userId, JSON.toJSONString(requestDTO));

            // 参数校验
            if (StringUtils.isBlank(userId) || StringUtils.isBlank(source) || StringUtils.isBlank(channel) 
                    || StringUtils.isBlank(goodsId) || null == activityId 
                    || ("HTTP".equals(notifyConfigVO.getNotifyType()) && StringUtils.isBlank(notifyConfigVO.getNotifyUrl()))) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 营销优惠试算
            TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(userId)
                    .source(source)
                    .channel(channel)
                    .goodsId(goodsId)
                    .activityId(activityId)
                    .build());

            // 人群限定
            if (!trialBalanceEntity.getIsVisible() || !trialBalanceEntity.getIsEnable()) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.E0007.getCode())
                        .info(ResponseCode.E0007.getInfo())
                        .build();
            }

            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();

            // 构建实体对象
            UserEntity userEntity = UserEntity.builder().userId(userId).build();
            // 热点商品不做拼团，teamId 设置为 null（会在 lockHotGoodsOrder 方法中生成虚拟 teamId）
            PayActivityEntity payActivityEntity = PayActivityEntity.builder()
                    .teamId(null)  // 热点商品不做拼团
                    .activityId(activityId)
                    .activityName(groupBuyActivityDiscountVO.getActivityName())
                    .startTime(groupBuyActivityDiscountVO.getStartTime())
                    .endTime(groupBuyActivityDiscountVO.getEndTime())
                    .validTime(groupBuyActivityDiscountVO.getValidTime())
                    .targetCount(groupBuyActivityDiscountVO.getTarget())
                    .build();
            PayDiscountEntity payDiscountEntity = PayDiscountEntity.builder()
                    .source(source)
                    .channel(channel)
                    .goodsId(goodsId)
                    .goodsName(trialBalanceEntity.getGoodsName())
                    .originalPrice(trialBalanceEntity.getOriginalPrice())
                    .deductionPrice(trialBalanceEntity.getDeductionPrice())
                    .payPrice(trialBalanceEntity.getPayPrice())
                    .outTradeNo(outTradeNo)
                    .notifyConfigVO(
                            // 构建回调通知对象
                            NotifyConfigVO.builder()
                                    .notifyType(NotifyTypeEnumVO.valueOf(notifyConfigVO.getNotifyType()))
                                    .notifyMQ(notifyConfigVO.getNotifyMQ())
                                    .notifyUrl(notifyConfigVO.getNotifyUrl())
                                    .build())
                    .build();

            // 调用热点商品下单服务
            log.info("热点商品下单: userId={}, activityId={}, goodsId={}", userId, activityId, goodsId);
            MarketPayOrderEntity marketPayOrderEntity = hotGoodsTradeService.lockHotGoodsOrder(userEntity, payActivityEntity, payDiscountEntity);

            log.info("热点商品下单成功:{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));

            // 返回结果
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(LockMarketPayOrderResponseDTO.builder()
                            .orderId(marketPayOrderEntity.getOrderId())
                            .originalPrice(marketPayOrderEntity.getOriginalPrice())
                            .deductionPrice(marketPayOrderEntity.getDeductionPrice())
                            .payPrice(marketPayOrderEntity.getPayPrice())
                            .tradeOrderStatus(marketPayOrderEntity.getTradeOrderStatusEnumVO().getCode())
                            .teamId(marketPayOrderEntity.getTeamId())
                            .build())
                    .build();
        } catch (AppException e) {
            log.error("热点商品下单业务异常:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("热点商品下单服务失败:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}

