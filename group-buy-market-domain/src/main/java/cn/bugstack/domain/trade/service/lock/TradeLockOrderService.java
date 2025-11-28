package cn.bugstack.domain.trade.service.lock;

import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.aggregate.GroupBuyOrderAggregate;
import cn.bugstack.domain.trade.model.entity.*;
import cn.bugstack.domain.trade.model.valobj.GroupBuyProgressVO;
import cn.bugstack.domain.trade.model.valobj.TradeOrderStatusEnumVO;
import cn.bugstack.domain.trade.model.vo.RedisStockLogVO;
import cn.bugstack.domain.trade.service.ITradeLockOrderService;
import cn.bugstack.domain.trade.service.lock.factory.TradeLockRuleFilterFactory;
import cn.bugstack.domain.trade.adapter.port.IMessageProducer;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.types.utils.SnowflakeIdUtil;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author liang.tian
 */
@Slf4j
@Service
public class TradeLockOrderService implements ITradeLockOrderService {

    @Resource
    private ITradeRepository repository;
    @Resource
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter;
    @Resource
    private IMessageProducer messageProducer;

    @Resource
    private IRedisAdapter redisAdapter;

    @Resource
    private IInventoryDeductionLogRepository inventoryDeductionLogRepository;

    // 旁路验证线程池
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(10);

    // 消息队列监听信道
    private static final String ORDER_CREATE_BINDING = "orderCreate-out-0";

    // 商品库存redis的前缀
    private static final String GOODS_STOCK_KEY = "group_buy_market_goods_stock_key_";
    // 商品库存流水的前缀
    private static final String GOODS_STOCK_LOG_KEY = "group_buy_market_goods_stock_log_key_";

    @Override
    public MarketPayOrderEntity queryNoPayMarketPayOrderByOutTradeNo(String userId, String outTradeNo) {
        log.info("拼团交易-查询未支付营销订单:{} outTradeNo:{}", userId, outTradeNo);
        return repository.queryMarketPayOrderEntityByOutTradeNo(userId, outTradeNo);
    }

    @Override
    public GroupBuyProgressVO queryGroupBuyProgress(String teamId) {
        log.info("拼团交易-查询拼单进度:{}", teamId);
        return repository.queryGroupBuyProgress(teamId);
    }

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {
        log.info("拼团交易-锁定营销优惠支付订单:{} activityId:{} goodsId:{}", userEntity.getUserId(), payActivityEntity.getActivityId(), payDiscountEntity.getGoodsId());

        // 1. 交易规则过滤（移除了 TeamStockOccupyRuleFilter）
        TradeLockRuleFilterBackEntity tradeLockRuleFilterBackEntity = tradeRuleFilter.apply(
                TradeLockRuleCommandEntity.builder()
                        .activityId(payActivityEntity.getActivityId())
                        .userId(userEntity.getUserId())
                        .teamId(payActivityEntity.getTeamId())
                        .goodsId(payDiscountEntity.getGoodsId())
                        .build(),
                new TradeLockRuleFilterFactory.DynamicContext()
        );

        // 已参与拼团量 - 用于构建数据库唯一索引使用，确保用户只能在一个活动上参与固定的次数
        Integer userTakeOrderCount = tradeLockRuleFilterBackEntity.getUserTakeOrderCount();

        // 队伍的目标量
        Integer targetCount = tradeLockRuleFilterBackEntity.getTargetCount();

        // 生成订单号（雪花算法）
        String orderId = SnowflakeIdUtil.nextIdStr();

        // 5.构建聚合对象（包含orderId）
        GroupBuyOrderAggregate groupBuyOrderAggregate = GroupBuyOrderAggregate.builder()
                .userEntity(userEntity)
                .payActivityEntity(payActivityEntity)
                .payDiscountEntity(payDiscountEntity)
                .userTakeOrderCount(userTakeOrderCount)
                .orderId(orderId)
                .targetCount(targetCount)
                .build();

        // 6. 构建库存扣减标识（使用orderId代替outTradeNo，更安全可靠）
        String teamId = payActivityEntity.getTeamId();
        String goodsStockLogKey = GOODS_STOCK_LOG_KEY + "_" + payActivityEntity.getActivityId() + "_" + payDiscountEntity.getGoodsId();
        String identifier = buildIdentifier(userEntity.getUserId(), orderId);


        // 7. 发送RocketMQ事务消息 (本地事务中预扣减库存)
        boolean sendResult = messageProducer.sendOrderCreateMessage(
                ORDER_CREATE_BINDING,   // 创建订单绑定
                orderId,  // 使用orderId作为消息唯一标识
                JSON.toJSONString(groupBuyOrderAggregate),
                groupBuyOrderAggregate
        );

        if (!sendResult) {
            log.error("发送RocketMQ事务消息失败: teamId={}, orderId={}", teamId, orderId);
            throw new RuntimeException("订单创建失败");
        }

        // 8. 确认预扣减结果
//        String decreaseLog = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
//        if (decreaseLog == null) {
//            log.error("未查询到库存扣减流水,预扣减失败: teamId={}, orderId={}", teamId, orderId);
//            throw new RuntimeException("库存预扣减失败");
//        }

        String userId = userEntity.getUserId();

        // ⭐ 新增：同步查询订单状态（参考 NFTurbo）
        // ⚠️ 注意：RocketMQ 事务消息的本地事务是同步执行的，但数据库事务可能还未提交
        // 因此需要添加重试机制，避免因查询时机过早导致误报"订单创建失败"
        MarketPayOrderEntity order = queryOrderWithRetry(userId, orderId, 3, 100);
        if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
            // 订单已创建成功，执行旁路验证
            bypassVerify(goodsStockLogKey, identifier, teamId, orderId, userId);
            return order;
        }
        
        // 订单查询失败，检查 Redis 流水来判断本地事务是否执行成功
        String decreaseLog = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
        if (decreaseLog != null) {
            // Redis 流水存在，说明本地事务已执行，但订单可能还未落库（数据库事务还未提交）
            // 再等待一段时间后重试查询订单（最多再重试2次，每次间隔200ms）
            log.warn("订单查询失败但Redis流水存在，订单可能正在创建中，继续重试: orderId={}, teamId={}", orderId, teamId);
            order = queryOrderWithRetry(userId, orderId, 2, 200);
            if (order != null && TradeOrderStatusEnumVO.CREATE.equals(order.getTradeOrderStatusEnumVO())) {
                // 订单已创建成功，执行旁路验证
                bypassVerify(goodsStockLogKey, identifier, teamId, orderId, userId);
                return order;
            }
            // 如果还是查询不到，说明可能是订单创建失败但 Redis 流水未清理
            // 这种情况应该触发告警，但先返回订单ID，让调用方稍后查询
            log.error("订单查询失败但Redis流水存在，可能是订单创建失败但流水未清理: orderId={}, teamId={}", orderId, teamId);
            throw new RuntimeException("订单创建中，请稍后查询订单状态");
        } else {
            // Redis 流水不存在，说明本地事务失败
            log.error("订单创建失败: orderId={}, teamId={}, Redis流水不存在", orderId, teamId);
            throw new RuntimeException("订单创建失败");
        }
    }

    /**
     * 旁路验证: 延迟检查Redis流水与DB的一致性（参考 NFTurbo）
     * 
     * 设计思路：
     * 1. 延迟3秒后执行，检查数据库库存流水表（而不是订单表）
     * 2. 库存流水表在本地事务中同步创建，更可靠
     * 3. ⭐ 检查数量一致性：只有 Redis 流水和数据库流水的扣减数量一致，才删除 Redis 流水
     * 4. 如果核验成功，删除 Redis 流水，快速清理
     * 5. 对账任务只处理3秒之前的数据，避免和旁路验证冲突
     */
    private void bypassVerify(String goodsStockLogKey, String identifier, String teamId, String orderId, String userId) {
        scheduler.schedule(() -> {
            try {
                log.info("旁路验证-开始: teamId={}, orderId={}", teamId, orderId);

                // 1. 查询Redis流水
                String redisLogStr = redisAdapter.getStockDecreaseLog(goodsStockLogKey, identifier);
                if (redisLogStr == null) {
                    // Redis 流水不存在，可能是已经被对账任务清理了，或者本地事务失败
                    log.warn("旁路验证-Redis流水不存在: teamId={}, orderId={}", teamId, orderId);
                    return;
                }

                // 2. 解析Redis流水
                RedisStockLogVO redisLog = JSON.parseObject(redisLogStr, RedisStockLogVO.class);
                if (redisLog == null || !"decrease".equalsIgnoreCase(redisLog.getAction())) {
                    log.warn("旁路验证-Redis流水格式错误或不是扣减操作: teamId={}, orderId={}", teamId, orderId);
                    return;
                }

                // 3. ⭐ 查询数据库库存流水表（参考 NFTurbo，检查库存流水表而不是订单表）
                // 库存流水表在本地事务中同步创建，更可靠，不会因为异步处理导致延迟
                InventoryDeductionLogEntity dbLog = inventoryDeductionLogRepository.queryByOrderId(orderId);

                if (dbLog == null) {
                    // 数据库库存流水不存在，可能是订单创建失败，等待对账任务处理
                    log.warn("旁路验证-未找到数据库库存流水: teamId={}, orderId={}", teamId, orderId);
                    return;
                }

                // 4. ⭐ 检查数量一致性（防御性检查）
                // 注意：在当前设计中，每次扣减都是 quantity=1，且有幂等性保护，数量应该总是一致的
                // 但保留此检查是为了：
                // 1. 防御性编程：防止未来代码变更（如支持批量扣减）导致的不一致
                // 2. 数据完整性验证：确保流水记录的数据是正确的
                // 3. 异常检测：如果出现数量不一致，说明系统有bug或异常
                Integer redisChange = redisLog.getChangeAsInteger();
                Integer dbQuantity = dbLog.getQuantity();

                if (redisChange == null || dbQuantity == null) {
                    log.warn("旁路验证-扣减数量为空: teamId={}, orderId={}, redisChange={}, dbQuantity={}",
                            teamId, orderId, redisChange, dbQuantity);
                    return;
                }

                if (!redisChange.equals(dbQuantity)) {
                    // ⚠️ 数量不一致：这不应该发生，说明系统有bug或数据被手动修改
                    // 不删除 Redis 流水，让对账任务检测到不一致并处理
                    log.error("旁路验证-扣减数量不一致（异常情况）: teamId={}, orderId={}, redisChange={}, dbQuantity={}",
                            teamId, orderId, redisChange, dbQuantity);
                    return;
                }

                // 5. 核验成功，数据一致，删除 Redis 流水
                redisAdapter.removeStockDecreaseLog(goodsStockLogKey, identifier);
                log.info("旁路验证-成功: teamId={}, orderId={}, quantity={}", teamId, orderId, dbQuantity);

            } catch (Exception e) {
                // 核验失败打印日志，不影响主流程，等异步任务再核对
                log.error("旁路验证-异常: teamId={}, orderId={}", teamId, orderId, e);
            }
        }, 3, TimeUnit.SECONDS); // ⭐ 改为3秒，和 NFTurbo 一致
    }

    /**
     * 构建库存扣减标识符（使用orderId代替outTradeNo，更安全可靠）
     */
    private String buildIdentifier(String userId, String orderId) {
        return "DECREASE_" + userId + "_" + orderId;
    }

    /**
     * 带重试的订单查询
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @param maxRetries 最大重试次数
     * @param retryIntervalMs 重试间隔（毫秒）
     * @return 订单实体，如果查询不到返回 null
     */
    private MarketPayOrderEntity queryOrderWithRetry(String userId, String orderId, int maxRetries, long retryIntervalMs) {
        for (int i = 0; i < maxRetries; i++) {
            MarketPayOrderEntity order = repository.queryMarketPayOrderEntityByOrderId(userId, orderId);
            if (order != null) {
                return order;
            }
            
            // 如果不是最后一次重试，等待一段时间后重试
            if (i < maxRetries - 1) {
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("订单查询重试被中断: orderId={}", orderId);
                    break;
                }
            }
        }
        
        return null;
    }
 

}
