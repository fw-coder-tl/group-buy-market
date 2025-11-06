package cn.bugstack.trigger.http;

import cn.bugstack.api.IMarketIndexService;
import cn.bugstack.api.dto.GoodsMarketRequestDTO;
import cn.bugstack.api.dto.GoodsMarketResponseDTO;
import cn.bugstack.api.dto.TokenRequestDTO;
import cn.bugstack.api.response.Response;
import cn.bugstack.domain.activity.model.entity.MarketProductEntity;
import cn.bugstack.domain.activity.model.entity.TrialBalanceEntity;
import cn.bugstack.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import cn.bugstack.domain.activity.model.valobj.GroupBuyActivityDiscountVO;
import cn.bugstack.domain.activity.model.valobj.TeamStatisticVO;
import cn.bugstack.domain.activity.service.IIndexGroupBuyMarketService;
import cn.bugstack.types.enums.ResponseCode;
import cn.bugstack.types.enums.TokenSceneEnum;
import cn.bugstack.types.utils.TokenUtil;
import cn.bugstack.wrench.rate.limiter.types.annotations.RateLimiterAccessInterceptor;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author liang.tian
 * @description 营销首页服务
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/index/")
public class MarketIndexController implements IMarketIndexService {

    @Resource
    private IIndexGroupBuyMarketService indexGroupBuyMarketService;
    @Resource
    private RedissonClient redissonClient;

    @RateLimiterAccessInterceptor(key = "userId", fallbackMethod = "queryGroupBuyMarketConfigFallBack", permitsPerSecond = 1.0d, blacklistCount = 1)
    @RequestMapping(value = "query_group_buy_market_config", method = RequestMethod.POST)
    @Override
    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfig(@RequestBody GoodsMarketRequestDTO requestDTO) {
        try {
            log.info("查询拼团营销配置开始:{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId());

            if (StringUtils.isBlank(requestDTO.getUserId()) || StringUtils.isBlank(requestDTO.getSource()) || StringUtils.isBlank(requestDTO.getChannel()) || StringUtils.isBlank(requestDTO.getGoodsId())) {
                return Response.<GoodsMarketResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 1. 营销优惠试算
            TrialBalanceEntity trialBalanceEntity = indexGroupBuyMarketService.indexMarketTrial(MarketProductEntity.builder()
                    .userId(requestDTO.getUserId())
                    .source(requestDTO.getSource())
                    .channel(requestDTO.getChannel())
                    .goodsId(requestDTO.getGoodsId())
                    .build());


            GroupBuyActivityDiscountVO groupBuyActivityDiscountVO = trialBalanceEntity.getGroupBuyActivityDiscountVO();
            Long activityId = groupBuyActivityDiscountVO.getActivityId();

            // 2. 查询拼团组队
            List<UserGroupBuyOrderDetailEntity> userGroupBuyOrderDetailEntities = indexGroupBuyMarketService.queryInProgressUserGroupBuyOrderDetailList(activityId, requestDTO.getUserId(), 1, 2);

            // 3. 统计拼团数据
            TeamStatisticVO teamStatisticVO = indexGroupBuyMarketService.queryTeamStatisticByActivityId(activityId);

            GoodsMarketResponseDTO.Goods goods = GoodsMarketResponseDTO.Goods.builder()
                    .goodsId(trialBalanceEntity.getGoodsId())
                    .originalPrice(trialBalanceEntity.getOriginalPrice())
                    .deductionPrice(trialBalanceEntity.getDeductionPrice())
                    .payPrice(trialBalanceEntity.getPayPrice())
                    .build();

            List<GoodsMarketResponseDTO.Team> teams = new ArrayList<>();
            if (null != userGroupBuyOrderDetailEntities && !userGroupBuyOrderDetailEntities.isEmpty()) {
                for (UserGroupBuyOrderDetailEntity userGroupBuyOrderDetailEntity : userGroupBuyOrderDetailEntities) {
                    GoodsMarketResponseDTO.Team team = GoodsMarketResponseDTO.Team.builder()
                            .userId(userGroupBuyOrderDetailEntity.getUserId())
                            .teamId(userGroupBuyOrderDetailEntity.getTeamId())
                            .activityId(userGroupBuyOrderDetailEntity.getActivityId())
                            .targetCount(userGroupBuyOrderDetailEntity.getTargetCount())
                            .completeCount(userGroupBuyOrderDetailEntity.getCompleteCount())
                            .lockCount(userGroupBuyOrderDetailEntity.getLockCount())
                            .validStartTime(userGroupBuyOrderDetailEntity.getValidStartTime())
                            .validEndTime(userGroupBuyOrderDetailEntity.getValidEndTime())
                            .validTimeCountdown(GoodsMarketResponseDTO.Team.differenceDateTime2Str(new Date(), userGroupBuyOrderDetailEntity.getValidEndTime()))
                            .outTradeNo(userGroupBuyOrderDetailEntity.getOutTradeNo())
                            .build();
                    teams.add(team);
                }
            }

            GoodsMarketResponseDTO.TeamStatistic teamStatistic = GoodsMarketResponseDTO.TeamStatistic.builder()
                    .allTeamCount(teamStatisticVO.getAllTeamCount())
                    .allTeamCompleteCount(teamStatisticVO.getAllTeamCompleteCount())
                    .allTeamUserCount(teamStatisticVO.getAllTeamUserCount())
                    .build();

            Response<GoodsMarketResponseDTO> response = Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(GoodsMarketResponseDTO.builder()
                            .activityId(activityId)
                            .goods(goods)
                            .teamList(teams)
                            .teamStatistic(teamStatistic)
                            .build())
                    .build();

            log.info("查询拼团营销配置完成:{} goodsId:{} response:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), JSON.toJSONString(response));

            return response;
        } catch (Exception e) {
            log.error("查询拼团营销配置失败:{} goodsId:{}", requestDTO.getUserId(), requestDTO.getGoodsId(), e);
            return Response.<GoodsMarketResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    public Response<GoodsMarketResponseDTO> queryGroupBuyMarketConfigFallBack(@RequestBody GoodsMarketRequestDTO requestDTO) {
        log.error("查询拼团营销配置限流:{}", requestDTO.getUserId());
        return Response.<GoodsMarketResponseDTO>builder()
                .code(ResponseCode.RATE_LIMITER.getCode())
                .info(ResponseCode.RATE_LIMITER.getInfo())
                .build();
    }

    /**
     * 获取Token - 用于防止订单重复提交
     * @param tokenRequestDTO 令牌请求参数
     * @return Token
     */
    @RequestMapping(value = "get_token", method = RequestMethod.GET)
    @Override
    public Response<String> getToken(TokenRequestDTO tokenRequestDTO) {
        try {
            log.info("获取Token开始: scene:{} userId:{} activityId:{}", tokenRequestDTO.getScene(), tokenRequestDTO.getUserId(), tokenRequestDTO.getActivityId());

            // 1. 校验场景类型
            TokenSceneEnum tokenScene = TokenSceneEnum.getByScene(tokenRequestDTO.getScene());
            if (tokenScene == null) {
                log.error("Token场景不存在: {}", tokenRequestDTO.getScene());
                return Response.<String>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("Token场景不存在")
                        .build();
            }

            // 2. 校验参数
            if (StringUtils.isBlank(tokenRequestDTO.getUserId()) || tokenRequestDTO.getActivityId() == null) {
                log.error("参数不合法: userId:{} activityId:{}", tokenRequestDTO.getUserId(), tokenRequestDTO.getActivityId());
                return Response.<String>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("参数不能为空")
                        .build();
            }

            // 3. 校验活动是否存在（防止攻击者传入随机的activityId）
            // 这里可以查询数据库验证activityId是否合法
            // 为了性能考虑，这里简化处理，实际项目中应该增加校验

            // 4. 生成Token
            // tokenKey格式: token:lock_order:activityId:userId
            String tokenKey = "token:" + tokenScene.getScene() + ":" + tokenRequestDTO.getActivityId() + ":" + tokenRequestDTO.getUserId();
            String tokenValue = TokenUtil.getTokenValueByKey(tokenKey);

            // 5. 存储到Redis，30分钟过期
            redissonClient.getBucket(tokenKey).set(tokenValue, 30, TimeUnit.MINUTES);

            log.info("获取Token成功: userId:{} activityId:{} token:{}", tokenRequestDTO.getUserId(), tokenRequestDTO.getActivityId(), tokenValue);

            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(tokenValue)
                    .build();
        } catch (Exception e) {
            log.error("获取Token失败: scene:{} userId:{} activityId:{}", tokenRequestDTO.getScene(), tokenRequestDTO.getUserId(), tokenRequestDTO.getActivityId(), e);
            return Response.<String>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取Token失败")
                    .build();
        }
    }

}
