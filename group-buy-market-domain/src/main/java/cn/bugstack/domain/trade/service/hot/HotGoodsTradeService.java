package cn.bugstack.domain.trade.service.hot;

import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.domain.trade.model.vo.RedisStockLogVO;
import cn.bugstack.domain.trade.service.IHotGoodsTradeService;
import cn.bugstack.types.utils.SnowflakeIdUtil;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 热点商品下单服务实现（Domain层）
 * 
 * 对标 NFTurbo 的 newBuyPlus
 * 
 * 特点：
 * 1. 不做拼团组队
 * 2. 不创建队伍
 * 3. 不扣减队伍库存
 * 4. 只扣减商品库存
 * 5. 高并发场景
 * 
 * @author liang.tian
 */
@Slf4j
@Service
public class HotGoodsTradeService implements IHotGoodsTradeService {

    // 消息队列 Topic（热点商品）
    private static final String HOT_GOODS_ORDER_CREATE_BINDING = "hotGoodsOrderCreate-out-0";

    // 商品库存流水 Redis 前缀
    private static final String GOODS_STOCK_LOG_KEY_PREFIX = "group_buy_market_goods_stock_log_";

    @Resource
    private ITradeRepository repository;

    // 已移除：hotGoodsTradeRuleFilter 不再在此处使用
    // 交易规则过滤已移至 HotGoodsOrderCreateTransactionListener.executeLocalTransaction 中执行

    @Resource
    private IMessageProducer messageProducer;

    @Resource
    private IRedisAdapter redisAdapter;

    @Resource
    private IInventoryDeductionLogRepository inventoryDeductionLogRepository;

    // 旁路验证线程池
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(10);

    @Override
    public MarketPayOrderEntity lockHotGoodsOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("热点商品下单-锁定订单: userId={}, activityId={}, goodsId={}", 
                userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        // ⚠️ 优化：移除发送消息前的交易规则过滤，减少数据库查询压力
        // 交易规则过滤（活动有效性、用户参与次数等）将在消息监听器的本地事务中执行
        // 这样可以先通过 Redis 和 MQ 进行流量拦截，减少数据库连接数和 CPU 压力

        // 生成订单号（雪花算法）
        String orderId = SnowflakeIdUtil.nextIdStr();

        // 构建聚合对象（不包含拼团相关字段）
        // userTakeOrderCount 将在消息监听器中通过交易规则过滤获取
        HotGoodsOrderAggregate hotGoodsOrderAggregate = HotGoodsOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(null) // 将在消息监听器中获取
                .orderId(orderId)
                .build();

        // 3. 构建库存扣减标识

        String identifier = buildIdentifier(userEntity.getUserId(), orderId);

        // 4. 发送 RocketMQ 事务消息（本地事务中同步执行：Redis 扣减 + 数据库扣减 + 订单创建）
        boolean sendResult = messageProducer.sendOrderCreateMessage(
                HOT_GOODS_ORDER_CREATE_BINDING,
                orderId,
                JSON.toJSONString(hotGoodsOrderAggregate),
                hotGoodsOrderAggregate
        );

        if (!sendResult) {
            log.error("发送RocketMQ事务消息失败: orderId={}", orderId);
            throw new RuntimeException("订单创建失败");
        }

        // 5. 查询订单状态（参考 NFTurbo newBuyPlus，只查询一次）
        String userId = userEntity.getUserId();
        MarketPayOrderEntity order = repository.queryMarketPayOrderEntityByOrderId(userId, orderId);
        
        if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
            // 订单已创建成功，执行旁路验证
            // 生成拼接流水前缀
            String goodsStockLogKey = GOODS_STOCK_LOG_KEY_PREFIX + payActivityEntity.getActivityId() + "_" + payDiscountEntity.getGoodsId();
            // 进行旁路验证
            bypassVerify(goodsStockLogKey, identifier, orderId, userId);
            return order;
        }

        // 6. 如果查询不到订单，可能是网络延迟或数据库异常，发送延迟检查消息（疑似废单）
        // 参考 NFTurbo：如果订单创建失败，发送 newBuyPlusPreCancel 延迟消息
        // 延迟检查：如果订单确实不存在，回滚库存；如果订单已创建（网络延迟），做补偿处理
        log.warn("订单查询失败，发送延迟检查消息（疑似废单）: orderId={}", orderId);
        messageProducer.sendDelayMessage(
                "hotGoodsOrderPreCancel",
                orderId,
                JSON.toJSONString(hotGoodsOrderAggregate),
                1 // 延迟1分钟（RocketMQ delayLevel 1 = 1分钟）
        );
        
        throw new RuntimeException("订单创建失败，已发送延迟检查消息");
    }

    /**
     * 旁路验证: 延迟检查Redis流水与DB的一致性（参考 NFTurbo）
     * 
     * 设计思路：
     * 1. 延迟3秒后执行，检查数据库库存流水表
     * 2. 检查数量一致性：只有 Redis 流水和数据库流水的扣减数量一致，才删除 Redis 流水
     * 3. 如果核验成功，删除 Redis 流水，快速清理
     */
    private void bypassVerify(String goodsStockLogKey, String identifier, String orderId, String userId) {
        scheduler.schedule(() -> {
            try {
                log.info("旁路验证-开始: orderId={}", orderId);

                // 1. 查询Redis流水
                String redisLogStr = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
                if (redisLogStr == null) {
                    log.warn("旁路验证-Redis流水不存在: orderId={}", orderId);
                    return;
                }

                // 2. 解析Redis流水
                RedisStockLogVO redisLog = JSON.parseObject(redisLogStr, RedisStockLogVO.class);
                if (redisLog == null || !"decrease".equalsIgnoreCase(redisLog.getAction())) {
                    log.warn("旁路验证-Redis流水格式错误或不是扣减操作: orderId={}", orderId);
                    return;
                }

                // 3. 查询数据库库存流水表
                InventoryDeductionLogEntity dbLog = inventoryDeductionLogRepository.queryByOrderId(orderId);
                if (dbLog == null) {
                    log.warn("旁路验证-未找到数据库库存流水: orderId={}", orderId);
                    return;
                }

                // 4. 检查数量一致性
                Integer redisChange = redisLog.getChangeAsInteger();
                Integer dbQuantity = dbLog.getQuantity();

                if (redisChange == null || dbQuantity == null) {
                    log.warn("旁路验证-扣减数量为空: orderId={}, redisChange={}, dbQuantity={}",
                            orderId, redisChange, dbQuantity);
                    return;
                }

                if (!redisChange.equals(dbQuantity)) {
                    log.error("旁路验证-扣减数量不一致（异常情况）: orderId={}, redisChange={}, dbQuantity={}",
                            orderId, redisChange, dbQuantity);
                    return;
                }

                // 5. 核验成功，数据一致，删除 Redis 流水
                redisAdapter.removeStockDecreaseLog(goodsStockLogKey, identifier);
                log.info("旁路验证-成功: orderId={}, quantity={}", orderId, dbQuantity);

            } catch (Exception e) {
                log.error("旁路验证-异常: orderId={}", orderId, e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * 构建库存扣减标识符
     */
    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }
}

