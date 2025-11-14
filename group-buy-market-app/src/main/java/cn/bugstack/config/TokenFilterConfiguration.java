package cn.bugstack.config;

import org.redisson.api.RedissonClient;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author liang.tian
 * @description Token过滤器配置
 * @create 2025-11-01
 */
@Configuration
public class TokenFilterConfiguration {

    /**
     * 注册Token过滤器
     * 只对锁单接口进行Token校验
     */
    @Bean
    public FilterRegistrationBean<TokenFilter> tokenFilter(RedissonClient redissonClient) {
        FilterRegistrationBean<TokenFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new TokenFilter(redissonClient));
        
        // 配置需要Token校验的URL
        registrationBean.addUrlPatterns("/api/v1/gbm/trade/lock_market_pay_order/1");
        
        // 设置过滤器顺序
        registrationBean.setOrder(10);
        
        return registrationBean;
    }
}

