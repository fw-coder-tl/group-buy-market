package cn.bugstack.trigger.job;

import cn.bugstack.domain.activity.model.entity.UserGroupBuyOrderDetailEntity;
import cn.bugstack.domain.trade.model.entity.TradeRefundCommandEntity;
import cn.bugstack.domain.trade.service.ITradeRefundOrderService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 超时未支付订单退单定时任务
 * @create 2025-01-31 15:00
 */
@Slf4j
@Service
public class TimeoutRefundJob {

    @Resource
    private ITradeRefundOrderService tradeRefundOrderService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 每5分钟执行一次超时订单扫描
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void exec() {
        // 分布式锁，防止多实例重复执行
        RLock lock = redissonClient.getLock("group_buy_market_timeout_refund_job_exec");
        try {
            // waitTime：等待获取锁的最长时间
            // leaseTime：租约时间，锁的持有时间
            boolean isLocked = lock.tryLock(3, 60, TimeUnit.SECONDS);
            if (!isLocked) {
                log.info("超时退单定时任务，获取锁失败，跳过本次执行");
                return;
            }

            log.info("超时退单定时任务开始执行");
            
            // 查询超时未支付订单列表
            List<UserGroupBuyOrderDetailEntity> timeoutOrderList = tradeRefundOrderService.queryTimeoutUnpaidOrderList();
            if (timeoutOrderList == null || timeoutOrderList.isEmpty()) {
                log.info("超时退单定时任务，未发现超时未支付订单");
                return;
            }

            log.info("超时退单定时任务，发现超时未支付订单数量：{}", timeoutOrderList.size());
            
            int successCount = 0;
            int failCount = 0;
            
            // 遍历处理每个超时订单
            for (UserGroupBuyOrderDetailEntity orderDetail : timeoutOrderList) {
                try {
                    // 构建退单命令
                    TradeRefundCommandEntity refundCommand = TradeRefundCommandEntity.builder()
                            .userId(orderDetail.getUserId())
                            .outTradeNo(orderDetail.getOutTradeNo())
                            .source(orderDetail.getSource())
                            .channel(orderDetail.getChannel())
                            .build();
                    
                    // 执行退单
                    tradeRefundOrderService.refundOrder(refundCommand);
                    successCount++;
                    
                    log.info("超时订单退单成功，用户ID：{}，交易单号：{}", orderDetail.getUserId(), orderDetail.getOutTradeNo());
                    
                } catch (Exception e) {
                    failCount++;
                    log.error("超时订单退单失败，用户ID：{}，交易单号：{}，错误信息：{}", 
                            orderDetail.getUserId(), orderDetail.getOutTradeNo(), e.getMessage(), e);
                }
            }
            
            log.info("超时退单定时任务执行完成，成功：{}，失败：{}", successCount, failCount);
            
        } catch (Exception e) {
            log.error("超时退单定时任务执行异常", e);
        } finally {
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}