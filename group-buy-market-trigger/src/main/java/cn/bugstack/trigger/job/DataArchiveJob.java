package cn.bugstack.trigger.job;

import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.adapter.repository.IInventoryDeductionLogRepository;
import cn.bugstack.domain.trade.model.entity.InventoryDeductionLogEntity;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 数据归档任务
 * 
 * 功能：
 * 1. 将订单表（group_buy_order_list）中3个月前的数据归档到归档表
 * 2. 将库存扣减流水表（inventory_deduction_log）中1个月前的数据归档到归档表
 * 3. 记录归档日志
 * 
 * 执行频率：建议每天凌晨2点执行一次
 * 归档策略：
 * - 订单表：归档3个月前的数据
 * - 库存流水表：归档1个月前的数据
 */
@Slf4j
@Component
public class DataArchiveJob {

    private static final String LOCK_KEY = "group_buy_market_data_archive_job";
    // 订单表归档天数（3个月）
    private static final int ORDER_ARCHIVE_DAYS = 90;
    // 库存流水表归档天数（1个月）
    private static final int INVENTORY_LOG_ARCHIVE_DAYS = 30;
    // 每批处理数量
    private static final int BATCH_SIZE = 1000;

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private ITradeRepository tradeRepository;
    @Resource
    private IInventoryDeductionLogRepository inventoryDeductionLogRepository;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 数据归档任务
     * 
     * 执行频率：建议每天凌晨2点执行一次
     */
    @XxlJob("dataArchiveJob")
    public ReturnT<String> exec() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 0, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("归档任务获取锁失败，跳过本次执行");
                return ReturnT.SUCCESS;
            }
            
            log.info("数据归档任务开始执行");
            
            // 1. 归档订单表
            int orderArchiveCount = archiveOrderTable();
            log.info("订单表归档完成，归档记录数: {}", orderArchiveCount);
            
            // 2. 归档库存扣减流水表
            int inventoryLogArchiveCount = archiveInventoryLogTable();
            log.info("库存扣减流水表归档完成，归档记录数: {}", inventoryLogArchiveCount);
            
            log.info("数据归档任务执行完成: 订单归档={}, 库存流水归档={}", 
                    orderArchiveCount, inventoryLogArchiveCount);
            
            return ReturnT.SUCCESS;
                    
        } catch (Exception e) {
            log.error("数据归档任务执行异常", e);
            return ReturnT.FAIL;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 归档订单表
     */
    private int archiveOrderTable() throws InterruptedException {
        LocalDate archiveDate = LocalDate.now().minusDays(ORDER_ARCHIVE_DAYS);
        Date archiveDateDate = Date.from(archiveDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        
        log.info("开始归档订单表，归档日期: {}", archiveDate);
        
        // 1. 查询需要归档的数据（使用SQL直接查询）
        List<java.util.Map<String, Object>> ordersToArchive = jdbcTemplate.queryForList(
            "SELECT id, user_id, team_id, order_id, activity_id, start_time, end_time, " +
            "goods_id, source, channel, original_price, deduction_price, pay_price, " +
            "status, out_trade_no, out_trade_time, biz_id, create_time, update_time " +
            "FROM group_buy_order_list " +
            "WHERE create_time < ? " +
            "ORDER BY id",
            archiveDateDate
        );
        
        if (ordersToArchive == null || ordersToArchive.isEmpty()) {
            log.info("订单表无数据需要归档");
            return 0;
        }
        
        int totalCount = ordersToArchive.size();
        int processedCount = 0;
        LocalDateTime startTime = LocalDateTime.now();
        
        try {
            // 2. 分批归档
            for (int i = 0; i < totalCount; i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, totalCount);
                List<java.util.Map<String, Object>> batch = ordersToArchive.subList(i, endIndex);
                
                // 插入归档表
                for (java.util.Map<String, Object> order : batch) {
                    jdbcTemplate.update(
                        "INSERT INTO group_buy_order_list_archive " +
                        "(user_id, team_id, order_id, activity_id, start_time, end_time, " +
                        "goods_id, source, channel, original_price, deduction_price, pay_price, " +
                        "status, out_trade_no, out_trade_time, biz_id, create_time, update_time, archive_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                        order.get("user_id"), order.get("team_id"), order.get("order_id"), order.get("activity_id"),
                        order.get("start_time"), order.get("end_time"), order.get("goods_id"), order.get("source"),
                        order.get("channel"), order.get("original_price"), order.get("deduction_price"),
                        order.get("pay_price"), order.get("status"), order.get("out_trade_no"),
                        order.get("out_trade_time"), order.get("biz_id"), order.get("create_time"), order.get("update_time")
                    );
                }
                
                // 删除原表数据
                for (java.util.Map<String, Object> order : batch) {
                    jdbcTemplate.update("DELETE FROM group_buy_order_list WHERE id = ?", order.get("id"));
                }
                
                processedCount += batch.size();
                log.info("订单表归档进度: {}/{}", processedCount, totalCount);
                
                // 避免长时间锁表，短暂休眠
                Thread.sleep(100);
            }
            
            // 3. 记录归档日志
            LocalDateTime endTime = LocalDateTime.now();
            jdbcTemplate.update(
                "INSERT INTO data_archive_log " +
                "(table_name, archive_date, archive_count, start_time, end_time, status) " +
                "VALUES (?, ?, ?, ?, ?, 'SUCCESS') " +
                "ON DUPLICATE KEY UPDATE archive_count=?, end_time=?, status='SUCCESS'",
                "group_buy_order_list", archiveDate, processedCount, startTime, endTime,
                processedCount, endTime
            );
            
            log.info("订单表归档完成，归档记录数: {}", processedCount);
            return processedCount;
            
        } catch (Exception e) {
            log.error("订单表归档失败", e);
            // 记录失败日志
            try {
                jdbcTemplate.update(
                    "INSERT INTO data_archive_log " +
                    "(table_name, archive_date, archive_count, start_time, end_time, status, error_message) " +
                    "VALUES (?, ?, ?, ?, NOW(), 'FAILED', ?) " +
                    "ON DUPLICATE KEY UPDATE status='FAILED', error_message=?",
                    "group_buy_order_list", archiveDate, processedCount, startTime, e.getMessage(), e.getMessage()
                );
            } catch (Exception ex) {
                log.error("记录归档日志失败", ex);
            }
            throw e;
        }
    }

    /**
     * 归档库存扣减流水表
     */
    private int archiveInventoryLogTable() throws InterruptedException {
        LocalDate archiveDate = LocalDate.now().minusDays(INVENTORY_LOG_ARCHIVE_DAYS);
        Date archiveDateDate = Date.from(archiveDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        
        log.info("开始归档库存扣减流水表，归档日期: {}", archiveDate);
        
        // 1. 查询需要归档的数据（使用SQL直接查询）
        List<java.util.Map<String, Object>> logsToArchive = jdbcTemplate.queryForList(
            "SELECT id, order_id, user_id, activity_id, goods_id, quantity, " +
            "before_saleable, after_saleable, before_frozen, after_frozen, " +
            "lock_version, status, create_time " +
            "FROM inventory_deduction_log " +
            "WHERE create_time < ? " +
            "ORDER BY id",
            archiveDateDate
        );
        
        if (logsToArchive == null || logsToArchive.isEmpty()) {
            log.info("库存扣减流水表无数据需要归档");
            return 0;
        }
        
        int totalCount = logsToArchive.size();
        int processedCount = 0;
        LocalDateTime startTime = LocalDateTime.now();
        
        try {
            // 2. 分批归档
            for (int i = 0; i < totalCount; i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, totalCount);
                List<java.util.Map<String, Object>> batch = logsToArchive.subList(i, endIndex);
                
                // 插入归档表
                for (java.util.Map<String, Object> log : batch) {
                    jdbcTemplate.update(
                        "INSERT INTO inventory_deduction_log_archive " +
                        "(order_id, user_id, activity_id, goods_id, quantity, " +
                        "before_saleable, after_saleable, before_frozen, after_frozen, " +
                        "lock_version, status, create_time, archive_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                        log.get("order_id"), log.get("user_id"), log.get("activity_id"), log.get("goods_id"),
                        log.get("quantity"), log.get("before_saleable"), log.get("after_saleable"),
                        log.get("before_frozen"), log.get("after_frozen"), log.get("lock_version"),
                        log.get("status"), log.get("create_time")
                    );
                }
                
                // 删除原表数据
                for (java.util.Map<String, Object> log : batch) {
                    jdbcTemplate.update("DELETE FROM inventory_deduction_log WHERE id = ?", log.get("id"));
                }
                
                processedCount += batch.size();
                log.info("库存扣减流水表归档进度: {}/{}", processedCount, totalCount);
                
                // 避免长时间锁表，短暂休眠
                Thread.sleep(100);
            }
            
            // 3. 记录归档日志
            LocalDateTime endTime = LocalDateTime.now();
            jdbcTemplate.update(
                "INSERT INTO data_archive_log " +
                "(table_name, archive_date, archive_count, start_time, end_time, status) " +
                "VALUES (?, ?, ?, ?, ?, 'SUCCESS') " +
                "ON DUPLICATE KEY UPDATE archive_count=?, end_time=?, status='SUCCESS'",
                "inventory_deduction_log", archiveDate, processedCount, startTime, endTime,
                processedCount, endTime
            );
            
            log.info("库存扣减流水表归档完成，归档记录数: {}", processedCount);
            return processedCount;
            
        } catch (Exception e) {
            log.error("库存扣减流水表归档失败", e);
            // 记录失败日志
            try {
                jdbcTemplate.update(
                    "INSERT INTO data_archive_log " +
                    "(table_name, archive_date, archive_count, start_time, end_time, status, error_message) " +
                    "VALUES (?, ?, ?, ?, NOW(), 'FAILED', ?) " +
                    "ON DUPLICATE KEY UPDATE status='FAILED', error_message=?",
                    "inventory_deduction_log", archiveDate, processedCount, startTime, e.getMessage(), e.getMessage()
                );
            } catch (Exception ex) {
                log.error("记录归档日志失败", ex);
            }
            throw e;
        }
    }
}

