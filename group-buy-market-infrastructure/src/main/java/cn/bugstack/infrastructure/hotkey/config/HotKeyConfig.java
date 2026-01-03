package cn.bugstack.infrastructure.hotkey.config;

import com.jd.platform.hotkey.client.ClientStarter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * JD HotKey 客户端配置
 * 
 * 初始化 HotKey 客户端，连接到 etcd 集群，启动热点探测上报管道
 * 
 * 参考 HotKey sample 项目的 Starter.java
 * 
 * @author liang.tian
 */
@Slf4j
@Configuration
public class HotKeyConfig {

    @Value("${hotkey.etcd.server:http://127.0.0.1:2379}")
    private String etcdServer;

    @Value("${hotkey.app-name:group-buy-market}")
    private String appName;

    @Value("${hotkey.caffeine-size:50000}")
    private Integer caffeineSize;

    @Value("${hotkey.push-period:500}")
    private Long pushPeriod;

    /**
     * 初始化 HotKey 客户端
     * 
     * 启动后，HotKey 客户端会：
     * 1. 连接到 etcd 集群获取配置
     * 2. 连接到 Worker 节点进行热点上报
     * 3. 接收 Worker 推送的热点 Key
     * 4. 将热点 Key 缓存到本地 Caffeine 缓存
     */
    @PostConstruct
    public void initHotkey() {
        try {
            log.info("开始初始化 HotKey 客户端: appName={}, etcdServer={}, caffeineSize={}, pushPeriod={}",
                    appName, etcdServer, caffeineSize, pushPeriod);

            ClientStarter.Builder builder = new ClientStarter.Builder();
            ClientStarter starter = builder
                    .setAppName(appName)
                    .setEtcdServer(etcdServer)
                    .setCaffeineSize(caffeineSize)
                    .setPushPeriod(pushPeriod)
                    .build();
            starter.startPipeline();

            log.info("HotKey 客户端初始化成功");
        } catch (Exception e) {
            log.error("HotKey 客户端初始化失败，将使用降级方案（本地缓存）", e);
        }
    }
}



