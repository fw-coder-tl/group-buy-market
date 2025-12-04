package cn.bugstack.trigger.http;

import cn.bugstack.api.IMarketTradeService;
import cn.bugstack.api.dto.*;
import cn.bugstack.api.response.Response;
import cn.bugstack.domain.activity.model.entity.MarketProductEntity;
import cn.bugstack.domain.activity.model.entity.TrialBalanceEntity;
import cn.bugstack.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import cn.bugstack.domain.activity.service.IIndexGroupBuyMarketService;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.model.valobj.NotifyConfigVO;
import cn.bugstack.domain.trade.model.valobj.NotifyTypeEnumVO;
import cn.bugstack.domain.trade.service.ITradeLockOrderService;
import cn.bugstack.domain.trade.service.ITradeRefundOrderService;
import cn.bugstack.domain.trade.service.ITradeSettlementOrderService;
import cn.bugstack.domain.trade.service.detector.IHotKeyDetector;
import cn.bugstack.domain.trade.service.IHotGoodsTradeService;
import cn.bugstack.domain.trade.service.normal.INormalGoodsTradeService;
import cn.bugstack.types.enums.ResponseCode;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;

/**
 * @author liang.tian
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/trade/")
public class MarketTradeController implements IMarketTradeService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    @Resource
    private ITradeLockOrderService tradeOrderService;
    @Resource
    private ITradeSettlementOrderService tradeSettlementOrderService;
    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;
    
    // 热点商品探测服务
    @Resource
    private IHotKeyDetector hotKeyDetector;
    
    // 热点商品下单服务
    @Resource
    private IHotGoodsTradeService hotGoodsTradeService;
    
    // 普通商品下单服务
    @Resource
    private INormalGoodsTradeService normalGoodsTradeService;

    /**
     * 拼团营销锁单
     */
    @RequestMapping(value = "lock_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO requestDTO) {
        try {
            // 参数
            String userId = requestDTO.getUserId();
            String source = requestDTO.getSource();
            String channel = requestDTO.getChannel();
            String goodsId = requestDTO.getGoodsId();
            Long activityId = requestDTO.getActivityId();
            String outTradeNo = requestDTO.getOutTradeNo();
            String teamId = requestDTO.getTeamId();
            LockMarketPayOrderRequestDTO.NotifyConfigVO notifyConfigVO = requestDTO.getNotifyConfigVO();

            log.info("营销交易锁单:{} LockMarketPayOrderRequestDTO:{}", userId, JSON.toJSONString(requestDTO));

            if (StringUtils.isBlank(userId) || StringUtils.isBlank(source) || StringUtils.isBlank(channel) || StringUtils.isBlank(goodsId) || null == activityId || ("HTTP".equals(notifyConfigVO.getNotifyType()) && StringUtils.isBlank(notifyConfigVO.getNotifyUrl()))) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 根据 hotkey 判断商品类型（提前判断，用于后续逻辑）
            boolean isHotGoods = hotKeyDetector.isHotGoods(activityId, goodsId);

            // 查询 outTradeNo 是否已经存在交易记录
            // todo (可以选择redis进行快速查询，避免数据库查询)

            // ⚠️ 注意：拼团是否已满的检查已在以下位置自动完成，无需提前查询数据库：
            // 1. Redis层：TeamStockOccupyRuleFilter 会检查 Redis 队伍库存，如果已满会抛出 E0008
            // 2. 数据库层：TradeRepository.updateAddLockCount 会检查数据库，如果已满会抛出 E0005
            // 3. 异常会被 catch (AppException e) 捕获并返回给前端
            // 移除提前查询可以避免高并发下的数据库压力

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
            PayActivityEntity payActivityEntity = PayActivityEntity.builder()
                    .teamId(teamId)
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

            // 根据 hotkey 判断走哪个接口（isHotGoods 已在前面定义）
            MarketPayOrderEntity marketPayOrderEntity = null;
            if (isHotGoods) {
                // 热点商品：走热点商品下单服务（不做拼团）
                log.info("热点商品下单: userId={}, activityId={}, goodsId={}", userId, activityId, goodsId);
                marketPayOrderEntity = hotGoodsTradeService.lockHotGoodsOrder(userEntity, payActivityEntity, payDiscountEntity);
            } else {
                // 普通商品：走普通商品下单服务（保留拼团）
                log.info("普通商品下单: userId={}, activityId={}, goodsId={}, teamId={}", userId, activityId, goodsId, teamId);
                marketPayOrderEntity = normalGoodsTradeService.lockNormalGoodsOrder(userEntity, payActivityEntity, payDiscountEntity);
            }

            log.info("交易锁单记录(新):{} marketPayOrderEntity:{}", userId, JSON.toJSONString(marketPayOrderEntity));

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
            log.error("营销交易锁单业务异常:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销交易锁单服务失败:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "settlement_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(@RequestBody SettlementMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("营销交易组队结算开始:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || null == requestDTO.getOutTradeTime()) {
                return Response.<SettlementMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 结算服务
            TradePaySettlementEntity tradePaySettlementEntity = tradeSettlementOrderService.settlementMarketPayOrder(TradePaySuccessEntity.builder()
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .userId(requestDTO.getUserId())
                    .outTradeNo(requestDTO.getOutTradeNo())
                    .outTradeTime(requestDTO.getOutTradeTime())
                    .build());

            SettlementMarketPayOrderResponseDTO responseDTO = SettlementMarketPayOrderResponseDTO.builder()
                    .userId(tradePaySettlementEntity.getUserId())
                    .teamId(tradePaySettlementEntity.getTeamId())
                    .activityId(tradePaySettlementEntity.getActivityId())
                    .outTradeNo(tradePaySettlementEntity.getOutTradeNo())
                    .build();

            // 返回结果
            Response<SettlementMarketPayOrderResponseDTO> response = Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("营销交易组队结算完成:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JSON.toJSONString(response));

            return response;
        } catch (AppException e) {
            log.error("营销交易组队结算异常:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销交易组队结算失败:{} LockMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<SettlementMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "refund_market_pay_order", method = RequestMethod.POST)
    @Override
    public Response<RefundMarketPayOrderResponseDTO> refundMarketPayOrder(@RequestBody RefundMarketPayOrderRequestDTO requestDTO) {
        try {
            log.info("营销拼团退单开始:{} outTradeNo:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getOutTradeNo()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel())) {
                return Response.<RefundMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 退单服务
            TradeRefundBehaviorEntity tradeRefundBehaviorEntity = tradeRefundOrderService.refundOrder(TradeRefundCommandEntity.builder()
                    .userId(requestDTO.getUserId())
                    .outTradeNo(requestDTO.getOutTradeNo())
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .build());

            RefundMarketPayOrderResponseDTO responseDTO = RefundMarketPayOrderResponseDTO.builder()
                    .userId(tradeRefundBehaviorEntity.getUserId())
                    .orderId(tradeRefundBehaviorEntity.getOrderId())
                    .teamId(tradeRefundBehaviorEntity.getTeamId())
                    .code(tradeRefundBehaviorEntity.getTradeRefundBehaviorEnum().getCode())
                    .info(tradeRefundBehaviorEntity.getTradeRefundBehaviorEnum().getInfo())
                    .build();

            // 返回结果
            Response<RefundMarketPayOrderResponseDTO> response = Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

            log.info("营销拼团退单完成:{} outTradeNo:{} response:{}", requestDTO.getUserId(), requestDTO.getOutTradeNo(), JSON.toJSONString(response));

            return response;
        } catch (AppException e) {
            log.error("营销拼团退单异常:{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("营销拼团退单失败:{} RefundMarketPayOrderRequestDTO:{}", requestDTO.getUserId(), JSON.toJSONString(requestDTO), e);
            return Response.<RefundMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 秒杀下单（不基于inventory hint的实现），热点商品，同步创建订单
     *
     * @param
     * @return 幂等号
     */
    @RequestMapping(value = "/newBuy", method = RequestMethod.POST)
    public Response<String> newBuy(@Valid @RequestBody BuyParam buyParam) {
        return null;
    }
}
