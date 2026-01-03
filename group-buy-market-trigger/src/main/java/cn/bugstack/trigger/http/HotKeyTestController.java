package cn.bugstack.trigger.http;

import cn.bugstack.api.response.Response;
import cn.bugstack.infrastructure.hotkey.IHotKeyDetector;
import cn.bugstack.types.enums.ResponseCode;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * HotKey 测试接口
 * 
 * 用于测试 JD HotKey 框架的集成效果
 * 
 * @author liang.tian
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/gbm/test/hotkey/")
public class HotKeyTestController {

    @Resource
    private IHotKeyDetector hotKeyDetector;

    /**
     * 检测商品是否为热点
     * 
     * 每次调用都会上报给 HotKey Worker
     * 当 2秒内调用超过10次，商品会被标记为热点
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     */
    @GetMapping("detect/{activityId}/{goodsId}")
    public Response<Map<String, Object>> detectHotGoods(
            @PathVariable Long activityId,
            @PathVariable String goodsId) {
        
        log.info("HotKey测试-检测热点: activityId={}, goodsId={}", activityId, goodsId);
        
        // 构建 HotKey
        String hotKey = "goods__" + activityId + "_" + goodsId;
        
        // 调用 JdHotKeyStore.isHotKey() 会自动上报
        boolean isHotByHotKey = JdHotKeyStore.isHotKey(hotKey);
        
        // 同时调用业务层检测
        boolean isHotByDetector = hotKeyDetector.isHotGoods(activityId, goodsId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("hotKey", hotKey);
        result.put("isHotByHotKey", isHotByHotKey);  // HotKey 框架检测结果
        result.put("isHotByDetector", isHotByDetector);  // 业务层检测结果
        result.put("timestamp", System.currentTimeMillis());
        
        log.info("HotKey测试-检测结果: hotKey={}, isHotByHotKey={}, isHotByDetector={}", 
                hotKey, isHotByHotKey, isHotByDetector);
        
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

    /**
     * 批量检测（用于快速触发热点）
     * 
     * @param activityId 活动ID
     * @param goodsId 商品ID
     * @param count 检测次数（默认15次，超过阈值10次）
     */
    @GetMapping("batch-detect/{activityId}/{goodsId}")
    public Response<Map<String, Object>> batchDetect(
            @PathVariable Long activityId,
            @PathVariable String goodsId,
            @RequestParam(defaultValue = "15") int count) {
        
        log.info("HotKey测试-批量检测: activityId={}, goodsId={}, count={}", activityId, goodsId, count);
        
        String hotKey = "goods__" + activityId + "_" + goodsId;
        boolean finalIsHot = false;
        
        // 快速调用多次，触发热点
        for (int i = 0; i < count; i++) {
            finalIsHot = JdHotKeyStore.isHotKey(hotKey);
            log.debug("批量检测 {}/{}: isHot={}", i + 1, count, finalIsHot);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("hotKey", hotKey);
        result.put("detectCount", count);
        result.put("isHotAfterBatch", finalIsHot);
        result.put("message", finalIsHot ? 
                "已检测为热点！后续请求将走热点链路" : 
                "尚未成为热点，请等待3秒后重试检测");
        result.put("timestamp", System.currentTimeMillis());
        
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(result)
                .build();
    }

}




