package cn.bugstack.trigger.listener;

import cn.bugstack.domain.trade.adapter.repository.ISkuRepository;
import cn.bugstack.domain.trade.model.aggregate.HotGoodsOrderAggregate;
import cn.bugstack.infrastructure.mq.consumer.AbstractStreamConsumer;
import cn.bugstack.infrastructure.mq.param.MessageBody;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 热点商品订单创建消息监听器（Trigger层）
 * 
 * 参考 NFTurbo 的 NewBuyPlusBatchMsgListener
 * 使用 Spring Cloud Stream 的 Consumer 方式
 * 
 * 重要说明：这个监听器用于正常流程的消息消费，批量消费消息并异步扣减数据库库存
 * 
 * 【完整链路说明】
 * 
 * 1. 正常流程（订单创建成功）：
 *    - RocketMQ 本地事务（HotGoodsOrderCreateTransactionListener.executeLocalTransaction）：
 *      a. 扣减 Redis 库存（在本地事务中）
 *      b. 创建订单（在本地事务中）
 *      c. 不扣减数据库库存（参考 NFTurbo：setSyncDecreaseInventory(false)）
 *    - 如果成功 → 返回 COMMIT_MESSAGE → 消息发送到 hotGoodsOrderCreate-out-0
 *    - 这个监听器消费消息：
 *      a. 批量消费消息（提高性能）
 *      b. 异步扣减数据库库存（TCC Try阶段：增加冻结库存）
 *      c. 带幂等性检查，如果已扣减过会直接返回成功
 * 
 * 2. 异常补偿流程（订单创建失败）：
 *    - 订单创建失败 → 发送延迟消息到 hotGoodsOrderPreCancel（30秒后检查）
 *    - HotGoodsOrderPreCancelListener 处理延迟消息：
 *      a. 如果订单已创建成功（状态为 CREATE）→ 说明之前查询失败（网络延迟或数据库异常）
 *         → 做补偿处理：扣减数据库库存（因为本地事务中只扣减了 Redis 库存）
 *      b. 如果订单不存在 → 回滚 Redis 库存
 * 
 * 【设计思路】
 * - 参考 NFTurbo：本地事务中不扣减数据库库存，减少本地事务时间
 * - 通过消息监听器异步扣减数据库库存，避免消息堆积
 * - 批量消费消息，提高性能
 * - 带幂等性检查，确保数据一致性
 * 
 * 【对比 NFTurbo】
 * - NFTurbo 的 NewBuyPlusBatchMsgListener 逻辑：
 *   - 批量消费 new-buy-plus-topic 消息
 *   - 调用 saleWithoutHint 扣减数据库库存
 * - 本实现完全对标 NFTurbo 的逻辑，但使用 Spring Cloud Stream 方式
 * 
 * @author liang.tian
 */
@Slf4j
@Component
public class HotGoodsOrderCreateMessageListener extends AbstractStreamConsumer {

    @Resource
    private ISkuRepository skuRepository;

    /**
     * 热点商品订单创建消息消费线程池
     * 参考 NFTurbo 的 newBuyPlusConsumePool
     */
    private final ThreadPoolExecutor consumePool = new ThreadPoolExecutor(
            10,  // 核心线程数
            20,  // 最大线程数
            60L, TimeUnit.SECONDS,  // 空闲线程存活时间
            new LinkedBlockingQueue<>(1000),  // 任务队列
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "hot-goods-create-consume-" + (++counter));
                    thread.setDaemon(false);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略：调用者运行
    );

    /**
     * 处理热点商品订单创建消息（正常流程）
     * 
     * 参考 NFTurbo 的 NewBuyPlusBatchMsgListener
     * 
     * 【触发场景】
     * - 订单在本地事务中创建成功，事务消息发送到 hotGoodsOrderCreate-out-0
     * - 这个监听器批量消费消息，并发处理，异步扣减数据库库存
     * 
     * 【处理逻辑】
     * 1. 批量接收消息（Spring Cloud Stream 支持批量消费）
     * 2. 使用线程池并发处理每条消息
     * 3. 扣减数据库库存（TCC Try阶段：增加冻结库存）
     * 4. 如果有一个失败，抛出异常，触发消息重试
     * 
     * 【批量消费】
     * - 使用 Consumer<Object> 支持单个消息和批量消息
     * - RocketMQ binder 可能传递单个消息或批量消息，需要兼容处理
     * - 参考 NFTurbo：批量大小 64，并发处理
     */
    @Bean
    Consumer<Object> hotGoodsOrderCreate() {
        return input -> {
            try {
                // 兼容处理：支持单个消息、批量消息和 JSONObject
                List<Message<MessageBody>> msgs = new ArrayList<>();
                
                if (input instanceof List) {
                    // 批量消息
                    @SuppressWarnings("unchecked")
                    List<Object> inputList = (List<Object>) input;
                    for (Object item : inputList) {
                        msgs.add(convertToMessage(item));
                    }
                } else {
                    // 单个消息，包装成 List
                    msgs.add(convertToMessage(input));
                }

                log.warn("热点商品订单创建消息-收到消息（正常流程），消息数量: {}", msgs.size());

                CompletionService<Boolean> completionService = new ExecutorCompletionService<>(consumePool);
                List<Future<Boolean>> futures = new ArrayList<>();
                // 用于记录失败的消息，方便排查
                final String[] failedOrderId = new String[1];

                // 1. 提交所有任务（参考 NFTurbo）
                msgs.forEach(msg -> {
                    Callable<Boolean> task = () -> {
                        try {
                            return doHotGoodsOrderCreateExecute(msg);
                        } catch (Exception e) {
                            // 记录失败的消息详情，方便排查
                            try {
                                HotGoodsOrderAggregate aggregate = getMessage(msg, HotGoodsOrderAggregate.class);
                                failedOrderId[0] = aggregate.getOrderId();
                                log.error("热点商品订单创建消息-任务处理失败: orderId={}", failedOrderId[0], e);
                            } catch (Exception ex) {
                                log.error("热点商品订单创建消息-任务处理失败（无法解析消息）", e);
                            }
                            return false; // 标记失败
                        }
                    };
                    futures.add(completionService.submit(task));
                });

                // 2. 检查结果（参考 NFTurbo：如果有一个失败立即终止）
                // 
                // 重要说明：RocketMQ 批量消费机制，如果有一条消息失败，整批消息都会重试
                // 
                // 幂等性保证（防止重复扣减）：
                // - decreaseSkuStock 方法会先查询流水表（inventory_deduction_log），如果流水已存在，直接返回 true
                // - 如果流水不存在，执行扣减并插入流水（唯一索引 orderId 保证幂等）
                // - 如果插入流水时发生唯一索引冲突，说明并发情况下已扣减成功，也返回 true
                // 
                // 因此，即使整批消息重试，已成功的消息也不会重复扣减库存：
                // - 第一次执行：成功扣减库存，插入流水
                // - 第二次执行（重试）：查询流水表，发现流水已存在，直接返回 true，不会重复扣减
                boolean allSuccess = true;
                try {
                    for (int i = 0; i < msgs.size(); i++) {
                        Future<Boolean> future = completionService.take();
                        if (!future.get()) { // 发现一个失败立即终止
                            allSuccess = false;
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.error("热点商品订单创建消息-批量处理异常", e);
                    allSuccess = false;
                }

                // 3. 如果有一个失败，抛出异常触发消息重试
                // 注意：整批消息都会重试，但由于幂等性保证，已成功的消息重复处理也没问题
                if (!allSuccess) {
                    log.error("热点商品订单创建消息-批量处理失败，部分消息处理失败，失败订单: {}, 整批消息将重试（由于幂等性保证，已成功的消息重复处理也没问题）", 
                            failedOrderId[0] != null ? failedOrderId[0] : "unknown");
                    throw new RuntimeException("热点商品订单创建消息-批量处理失败，部分消息处理失败，失败订单: " + 
                            (failedOrderId[0] != null ? failedOrderId[0] : "unknown"));
                }

                log.info("热点商品订单创建消息-批量处理成功，消息数量: {}", msgs.size());
            } catch (Exception e) {
                log.error("热点商品订单创建消息-批量处理失败", e);
                throw new RuntimeException("热点商品订单创建消息批量处理失败", e);
            }
        };
    }

    /**
     * 将输入对象转换为 Message<MessageBody>
     * 支持 Message<MessageBody>、JSONObject 等类型
     */
    private Message<MessageBody> convertToMessage(Object input) {
        if (input instanceof Message) {
            // 已经是 Message 类型
            @SuppressWarnings("unchecked")
            Message<MessageBody> msg = (Message<MessageBody>) input;
            return msg;
        } else if (input instanceof JSONObject) {
            // JSONObject 类型，需要转换为 Message<MessageBody>
            JSONObject jsonObject = (JSONObject) input;
            MessageBody messageBody = JSON.parseObject(jsonObject.toJSONString(), MessageBody.class);
            return MessageBuilder.withPayload(messageBody).build();
        } else {
            log.error("热点商品订单创建消息-不支持的消息类型: {}", input.getClass().getName());
            throw new RuntimeException("热点商品订单创建消息-不支持的消息类型: " + input.getClass().getName());
        }
    }

    /**
     * 执行单条消息处理
     * 参考 NFTurbo 的 doNewBuyPlusExecute
     */
    private boolean doHotGoodsOrderCreateExecute(Message<MessageBody> msg) {
        // 1. 解析消息（参考 NFTurbo）
        HotGoodsOrderAggregate aggregate = getMessage(msg, HotGoodsOrderAggregate.class);
        String orderId = aggregate.getOrderId();
        String userId = aggregate.getUserEntity().getUserId();
        Long activityId = aggregate.getPayActivityEntity().getActivityId();
        String goodsId = aggregate.getPayDiscountEntity().getGoodsId();

        // 2. 扣减数据库库存（TCC Try阶段：增加冻结库存，不减少可售库存）
        // 参考 NFTurbo: goodsFacadeService.saleWithoutHint() - 扣减数据库库存
        // 
        // 幂等性保证（防止重复扣减）：
        // 1. decreaseSkuStock 方法会先查询流水表（inventory_deduction_log），如果流水已存在，直接返回 true
        // 2. 如果流水不存在，执行扣减并插入流水（唯一索引 orderId 保证幂等）
        // 3. 如果插入流水时发生唯一索引冲突（DuplicateKeyException），说明并发情况下已扣减成功，也返回 true
        // 
        // 因此，即使整批消息重试，已成功的消息也不会重复扣减库存
        boolean decreaseResult = skuRepository.decreaseSkuStock(activityId, goodsId, 1, orderId, userId);
        if (!decreaseResult) {
            // 扣减失败（库存不足、乐观锁冲突等），返回 false 触发重试
            log.warn("热点商品订单创建消息-数据库库存扣减失败: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId);
            return false;
        } else {
            log.info("热点商品订单创建消息-数据库库存扣减成功: orderId={}, activityId={}, goodsId={}", 
                    orderId, activityId, goodsId);
            return true;
        }
    }
}

