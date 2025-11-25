package cn.bugstack.trigger.http;

import cn.bugstack.api.response.Response;
import cn.bugstack.domain.trade.adapter.port.IRedisAdapter;
import cn.bugstack.domain.trade.adapter.repository.ITradeRepository;
import cn.bugstack.domain.trade.model.entity.GroupBuyTeamEntity;
import cn.bugstack.types.enums.ResponseCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存预热控制器
 * 用于批量初始化团队库存到Redis
 */
@Slf4j
@RestController
@RequestMapping("/api/stock/preheat")
public class StockPreheatController {

    @Resource
    private IRedisAdapter redisAdapter;
    @Resource
    private ITradeRepository tradeRepository;

    /**
     * 批量初始化团队库存
     * @param request 包含teamId和stockCount的列表
     * @return 成功初始化的数量
     */
    @PostMapping("/batch")
    public Response<Integer> batchInitStock(@RequestBody BatchInitStockRequest request) {
        try {
            if (request == null || request.getTeamStocks() == null || request.getTeamStocks().isEmpty()) {
                return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), 0);
            }

            Map<String, Integer> teamStockMap = new HashMap<>();
            for (TeamStockItem item : request.getTeamStocks()) {
                if (item.getTeamId() != null && item.getStockCount() != null && item.getStockCount() > 0) {
                    teamStockMap.put(item.getTeamId(), item.getStockCount());
                }
            }

            if (teamStockMap.isEmpty()) {
                return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), 0);
            }

            int successCount = redisAdapter.batchInitTeamStock(teamStockMap);
            log.info("批量初始化团队库存: 总数={}, 成功={}", teamStockMap.size(), successCount);
            
            return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), successCount);
        } catch (Exception e) {
            log.error("批量初始化团队库存异常", e);
            return new Response<>(ResponseCode.UN_ERROR.getCode(), "批量初始化失败: " + e.getMessage(), 0);
        }
    }

    /**
     * 根据团队ID初始化库存（从数据库查询targetCount作为库存）
     * @param teamId 团队ID
     * @return 是否成功
     */
    @PostMapping("/init/{teamId}")
    public Response<Boolean> initStockByTeamId(@PathVariable String teamId) {
        try {
            GroupBuyTeamEntity team = tradeRepository.queryGroupBuyTeamByTeamId(teamId);
            if (team == null) {
                return new Response<>(ResponseCode.UN_ERROR.getCode(), "团队不存在: " + teamId, Boolean.FALSE);
            }

            if (team.getTargetCount() == null || team.getTargetCount() <= 0) {
                return new Response<>(ResponseCode.UN_ERROR.getCode(), "团队目标数量无效: " + teamId, Boolean.FALSE);
            }

            boolean success = redisAdapter.initTeamStock(teamId, team.getTargetCount());
            log.info("初始化团队库存: teamId={}, targetCount={}, success={}", teamId, team.getTargetCount(), success);
            
            return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), success);
        } catch (Exception e) {
            log.error("初始化团队库存异常: teamId={}", teamId, e);
            return new Response<>(ResponseCode.UN_ERROR.getCode(), "初始化失败: " + e.getMessage(), Boolean.FALSE);
        }
    }

    /**
     * 单个团队库存初始化
     * @param request 包含teamId和stockCount
     * @return 是否成功
     */
    @PostMapping("/init")
    public Response<Boolean> initStock(@RequestBody TeamStockItem request) {
        try {
            if (request == null || request.getTeamId() == null || request.getStockCount() == null || request.getStockCount() <= 0) {
                return new Response<>(ResponseCode.UN_ERROR.getCode(), "参数无效", Boolean.FALSE);
            }

            boolean success = redisAdapter.initTeamStock(request.getTeamId(), request.getStockCount());
            log.info("初始化团队库存: teamId={}, stockCount={}, success={}", request.getTeamId(), request.getStockCount(), success);
            
            return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), success);
        } catch (Exception e) {
            log.error("初始化团队库存异常: teamId={}", request != null ? request.getTeamId() : "null", e);
            return new Response<>(ResponseCode.UN_ERROR.getCode(), "初始化失败: " + e.getMessage(), Boolean.FALSE);
        }
    }

    @Data
    public static class BatchInitStockRequest {
        private List<TeamStockItem> teamStocks;
    }

    @Data
    public static class TeamStockItem {
        private String teamId;
        private Integer stockCount;
    }
}

