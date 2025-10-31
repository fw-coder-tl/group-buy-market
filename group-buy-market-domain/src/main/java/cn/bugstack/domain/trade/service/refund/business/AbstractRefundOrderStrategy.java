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
     * @param notifyTaskEntity 通知任务实体
     * @param refundType 退单类型描述
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
     * 通用库存恢复逻辑
     * @param teamRefundSuccess 团队退单成功信息
     * @param refundType 退单类型描述
     * @throws Exception 异常
     */
    protected void doReverseStock(TeamRefundSuccess teamRefundSuccess, String refundType) throws Exception {
        log.info("退单；恢复锁单量 - {} {} {} {}", refundType, teamRefundSuccess.getUserId(), teamRefundSuccess.getActivityId(), teamRefundSuccess.getTeamId());
        // 1. 恢复库存key
        String recoveryTeamStockKey = TradeLockRuleFilterFactory.generateRecoveryTeamStockKey(teamRefundSuccess.getActivityId(), teamRefundSuccess.getTeamId());
        // 2. 退单恢复库存
        repository.refund2AddRecovery(recoveryTeamStockKey, teamRefundSuccess.getOrderId());
    }

}