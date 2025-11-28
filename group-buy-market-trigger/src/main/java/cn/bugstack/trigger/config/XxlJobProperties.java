package cn.bugstack.trigger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XXL-Job 配置属性
 * 
 * @author liang.tian
 */
@Data
@ConfigurationProperties(prefix = "xxl.job")
public class XxlJobProperties {

    /** 是否启用 XXL-Job */
    private boolean enabled = true;

    /** XXL-Job 管理平台地址 */
    private String adminAddresses;

    /** 执行器应用名称 */
    private String appName;

    /** 访问令牌 */
    private String accessToken;

    /** 执行器 IP（可选，为空则自动获取） */
    private String ip;

    /** 执行器端口（可选，默认 9999） */
    private int port = 9999;

    /** 日志路径 */
    private String logPath = "/data/applogs/xxl-job/jobhandler";

    /** 日志保留天数 */
    private int logRetentionDays = 30;
}

