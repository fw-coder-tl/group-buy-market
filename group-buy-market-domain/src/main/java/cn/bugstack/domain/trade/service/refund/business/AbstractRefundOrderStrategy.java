package cn.bugstack.domain.trade.service.refund.business;

import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.entity.NotifyTaskEntity;
import cn.bugstack.domain.trade.model.valobj.TeamRefundSuccess;
import cn.bugstack.domain.trade.service.ITradeTaskService;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 退单策略抽象基类
 * 提供共用的依赖注入和MQ消息发送功能
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * @create 2025-01-01 00:00
 */
@Slf4j
public abstract class AbstractRefundOrderStrategy implements IRefundOrderStrategy {

    @Resource
    protected ITradeRepository repository;

    @Resource
    protected ITradeTaskService tradeTaskService;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    /**
     * 异步发送MQ消息
     */
    protected void sendRefundNotifyMessage(NotifyTaskEntity notifyTaskEntity, String refundType) {
        if (null != notifyTaskEntity) {
            threadPoolExecutor.execute(() -> {
                Map<String, Integer> notifyResultMap = null;
                try {
                    notifyResultMap = tradeTaskService.execNotifyJob(notifyTaskEntity);
                    log.info("回调通知交易退单({}) result:{}", refundType, JSON.toJSONString(notifyResultMap));
                } catch (Exception e) {
                    log.error("回调通知交易退单失败({}) result:{}", refundType, JSON.toJSONString(notifyResultMap), e);
                    throw new AppException(e.getMessage());
                }
            });
        }
    }

    /**
     * 通用库存恢复逻辑（队伍库存 + SKU库存）
     */
    protected void doReverseStock(TeamRefundSuccess teamRefundSuccess, String refundType) throws Exception {
        log.info("退单；恢复库存 - {} userId:{} activityId:{} teamId:{} orderId:{}",
                refundType,
                teamRefundSuccess.getUserId(),
                teamRefundSuccess.getActivityId(),
                teamRefundSuccess.getTeamId(),
                teamRefundSuccess.getOrderId());

        // 1. 恢复队伍库存
        String recoveryTeamStockKey = TradeLockRuleFilterFactory.generateRecoveryTeamStockKey(
                teamRefundSuccess.getActivityId(),
                teamRefundSuccess.getTeamId()
        );
        repository.refund2AddRecovery(recoveryTeamStockKey, teamRefundSuccess.getOrderId());

        // 2. 恢复SKU库存（新增）
        repository.refund2ReleaseSkuStock(
                teamRefundSuccess.getActivityId(),
                teamRefundSuccess.getGoodsId(),  // 需要在 TeamRefundSuccess 中添加 goodsId 字段
                teamRefundSuccess.getOrderId(),
                1  // 恢复数量
        );

        log.info("退单；恢复库存完成 - {} orderId:{}", refundType, teamRefundSuccess.getOrderId());
    }
}