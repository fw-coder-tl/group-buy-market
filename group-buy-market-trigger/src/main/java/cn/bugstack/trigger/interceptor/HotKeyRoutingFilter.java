package cn.bugstack.trigger.interceptor;

import cn.bugstack.infrastructure.hotkey.IHotKeyDetector;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import javax.servlet.*;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * HotKey动态路由过滤器（用于POST请求Body读取）
 * 
 * 参考JD HotKey开源项目的设计思路：
 * - 在请求到达Controller之前，读取POST Body中的商品信息
 * - 通过HotKeyDetector判断商品是否为热点商品
 * - 将路由标识存入Request属性，供Controller使用
 * 
 * 与拦截器的区别：
 * - Filter可以读取和修改Request Body
 * - Interceptor只能读取Request参数，无法直接读取POST Body
 * 
 * @author liang.tian
 */
@Slf4j
@Component
@Order(1) // 设置优先级，确保在其他Filter之前执行
public class HotKeyRoutingFilter implements Filter {

    @Resource
    private IHotKeyDetector hotKeyDetector;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestURI = httpRequest.getRequestURI();
        
        // 只处理秒杀下单接口
        if (!requestURI.contains("/lock_market_pay_order") && 
            !requestURI.contains("/lock_order")) {
            chain.doFilter(request, response);
            return;
        }

        // 只处理POST请求（GET请求参数在URL中，拦截器可以处理）
        if (!"POST".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // 读取Request Body
            byte[] bodyBytes = StreamUtils.copyToByteArray(httpRequest.getInputStream());
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            
            if (body.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }

            // 解析JSON Body，提取商品信息
            // 注意：这里简化处理，实际应该使用更健壮的JSON解析
            LockMarketPayOrderRequest requestBody = JSON.parseObject(body, LockMarketPayOrderRequest.class);
            
            if (requestBody != null && requestBody.getActivityId() != null && requestBody.getGoodsId() != null) {
                Long activityId = requestBody.getActivityId();
                String goodsId = requestBody.getGoodsId();
                
                // 使用HotKeyDetector判断是否为热点商品
                // 内部实现：先查本地缓存（Caffeine），再查Redis Set
                boolean isHotGoods = hotKeyDetector.isHotGoods(activityId, goodsId);
                
                // 将路由标识存入Request属性
                httpRequest.setAttribute("isHotGoods", isHotGoods);
                httpRequest.setAttribute("activityId", activityId);
                httpRequest.setAttribute("goodsId", goodsId);
                
                log.info("HotKey路由判断: activityId={}, goodsId={}, isHotGoods={}, URI={}", 
                        activityId, goodsId, isHotGoods, requestURI);
                
            }
            
            // 重新包装Request，使Body可以再次读取
            HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(httpRequest) {
                @Override
                public ServletInputStream getInputStream() throws IOException {
                    return new CachedBodyServletInputStream(bodyBytes);
                }
                
                @Override
                public BufferedReader getReader() throws IOException {
                    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                }
            };
            
            chain.doFilter(wrappedRequest, response);
            
        } catch (Exception e) {
            log.warn("HotKey路由判断失败，使用默认路由: {}", e.getMessage());
            // 异常情况下，默认走普通商品路由
            httpRequest.setAttribute("isHotGoods", false);
            chain.doFilter(request, response);
        }
    }

    /**
     * 简化的请求DTO，用于解析Body
     */
    private static class LockMarketPayOrderRequest {
        private Long activityId;
        private String goodsId;
        
        public Long getActivityId() {
            return activityId;
        }
        
        public void setActivityId(Long activityId) {
            this.activityId = activityId;
        }
        
        public String getGoodsId() {
            return goodsId;
        }
        
        public void setGoodsId(String goodsId) {
            this.goodsId = goodsId;
        }
    }

    /**
     * 缓存Body的ServletInputStream实现
     */
    private static class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream buffer;

        public CachedBodyServletInputStream(byte[] contents) {
            this.buffer = new ByteArrayInputStream(contents);
        }

        // 从缓存buffer里面读取，而不是原始的 InputStream
        @Override
        public int read() throws IOException {
            return buffer.read();
        }

        // 判断是都读完
        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        // 是否可以无阻塞读取
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // 不需要实现
        }
    }
}

