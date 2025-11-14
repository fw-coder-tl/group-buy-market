# RocketMQ Docker 部署文档

## 📋 目录结构

```
rocketmq/
├── broker/
│   ├── conf/
│   │   └── broker.conf      # Broker 配置文件
│   ├── logs/                # Broker 日志目录
│   └── store/               # Broker 数据存储目录
├── namesrv/
│   ├── logs/                # NameServer 日志目录
│   └── store/               # NameServer 数据存储目录
├── start-rocketmq.sh        # Linux/Mac 启动脚本
├── start-rocketmq.bat       # Windows 启动脚本
└── README.md                # 本文档
```

## 🚀 快速开始

### 方式一：使用脚本启动（推荐）

#### Windows 系统

```powershell
# 进入 rocketmq 目录
cd docs/dev-ops/rocketmq

# 启动 RocketMQ
.\start-rocketmq.bat start

# 查看状态
.\start-rocketmq.bat status

# 查看日志
.\start-rocketmq.bat logs

# 停止 RocketMQ
.\start-rocketmq.bat stop
```

#### Linux/Mac 系统

```bash
# 进入 rocketmq 目录
cd docs/dev-ops/rocketmq

# 给脚本添加执行权限
chmod +x start-rocketmq.sh

# 启动 RocketMQ
./start-rocketmq.sh start

# 查看状态
./start-rocketmq.sh status

# 查看日志
./start-rocketmq.sh logs

# 停止 RocketMQ
./start-rocketmq.sh stop
```

### 方式二：使用 Docker Compose 命令

```bash
# 进入 dev-ops 目录
cd docs/dev-ops

# 启动 RocketMQ
docker-compose -f docker-compose-rocketmq.yml up -d

# 查看容器状态
docker-compose -f docker-compose-rocketmq.yml ps

# 查看日志
docker-compose -f docker-compose-rocketmq.yml logs -f

# 停止 RocketMQ
docker-compose -f docker-compose-rocketmq.yml down
```

## 🌐 访问地址

启动成功后，可以通过以下地址访问：

| 服务 | 地址 | 说明 |
|-----|------|------|
| **NameServer** | `127.0.0.1:9876` | 命名服务，客户端连接地址 |
| **Broker** | `127.0.0.1:10911` | 消息代理服务 |
| **Dashboard** | http://127.0.0.1:18080 | Web 管理控制台 |

### 访问 Dashboard

1. 打开浏览器访问：http://127.0.0.1:18080
2. 可以查看：
   - 集群状态
   - Topic 列表
   - 消费者组
   - 消息查询
   - 运维操作

## 📝 配置说明

### Broker 配置文件

配置文件位置：`rocketmq/broker/conf/broker.conf`

重要配置项说明：

```properties
# Broker 对外暴露的IP地址（重要）
brokerIP1 = 127.0.0.1

# 本地开发：使用 127.0.0.1 或 localhost
# 服务器部署：使用服务器的实际IP地址
# Docker 部署：使用宿主机IP地址

# NameServer 地址
namesrvAddr = rocketmq-namesrv:9876

# 是否自动创建 Topic（开发环境建议开启）
autoCreateTopicEnable = true

# 消息存储路径
storePathRootDir = /home/rocketmq/store
```

### 修改配置后重启

```bash
# 方式一：使用脚本
.\start-rocketmq.bat restart

# 方式二：使用 Docker Compose
docker-compose -f docker-compose-rocketmq.yml restart
```

## 🔧 常用操作

### 1. 查看容器状态

```bash
docker ps | findstr rocketmq
```

### 2. 查看 NameServer 日志

```bash
docker logs -f rocketmq-namesrv
```

### 3. 查看 Broker 日志

```bash
docker logs -f rocketmq-broker
```

### 4. 进入容器内部

```bash
# 进入 Broker 容器
docker exec -it rocketmq-broker bash

# 进入 NameServer 容器
docker exec -it rocketmq-namesrv bash
```

### 5. 清理所有数据（慎用）

```bash
# 使用脚本清理
.\start-rocketmq.bat clean

# 或手动清理
docker-compose -f docker-compose-rocketmq.yml down -v
```

## 💻 Java 客户端使用示例

### 1. 添加 Maven 依赖

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.2.3</version>
</dependency>
```

### 2. 配置文件

```yaml
# application.yml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: my-producer-group
    send-message-timeout: 3000
  consumer:
    group: my-consumer-group
```

### 3. 发送消息

```java
@Component
public class RocketMQProducer {
    
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    
    public void sendMessage(String topic, String message) {
        rocketMQTemplate.convertAndSend(topic, message);
    }
}
```

### 4. 接收消息

```java
@Component
@RocketMQMessageListener(
    topic = "test-topic",
    consumerGroup = "my-consumer-group"
)
public class RocketMQConsumer implements RocketMQListener<String> {
    
    @Override
    public void onMessage(String message) {
        System.out.println("收到消息: " + message);
    }
}
```

## 🐛 常见问题

### 1. 容器启动失败

**问题**：容器无法启动或频繁重启

**解决方案**：
- 检查端口是否被占用：`netstat -ano | findstr "9876"`
- 查看容器日志：`docker logs rocketmq-namesrv`
- 确保 Docker 有足够的内存（建议至少 4GB）

### 2. 客户端连接失败

**问题**：Java 客户端无法连接到 RocketMQ

**解决方案**：
- 检查 `broker.conf` 中的 `brokerIP1` 配置
- 本地开发使用 `127.0.0.1`
- 服务器部署使用实际 IP 地址
- 确保防火墙开放了相关端口

### 3. Dashboard 无法访问

**问题**：无法打开 http://127.0.0.1:18080

**解决方案**：
- 检查容器是否启动：`docker ps | findstr dashboard`
- 查看容器日志：`docker logs rocketmq-dashboard`
- 等待容器完全启动（约 30 秒）

### 4. 消息发送失败

**问题**：发送消息时报错 `No route info`

**解决方案**：
- 确保 Broker 已启动并注册到 NameServer
- 检查 Topic 是否存在（Dashboard 中查看）
- 如果开启了 `autoCreateTopicEnable`，首次发送会自动创建

### 5. 磁盘空间不足

**问题**：消息堆积导致磁盘空间不足

**解决方案**：
- 修改 `broker.conf` 中的 `fileReservedTime`（默认 48 小时）
- 手动清理过期数据：`.\start-rocketmq.bat clean`

## 📊 性能调优

### 内存配置

编辑 `docker-compose-rocketmq.yml`：

```yaml
# NameServer 内存配置
rocketmq-namesrv:
  environment:
    JAVA_OPT_EXT: "-Xms512M -Xmx512M -Xmn256m"

# Broker 内存配置
rocketmq-broker:
  environment:
    JAVA_OPT_EXT: "-Xms1024M -Xmx1024M -Xmn512m"
```

### 刷盘策略

编辑 `broker.conf`：

```properties
# 异步刷盘（高性能，可能丢消息）
flushDiskType = ASYNC_FLUSH

# 同步刷盘（高可靠，性能较低）
flushDiskType = SYNC_FLUSH
```

## 🔐 生产环境建议

1. **关闭自动创建 Topic**
   ```properties
   autoCreateTopicEnable = false
   ```

2. **配置主从复制**
   - 部署多个 Broker 实例
   - 配置 Master-Slave 架构

3. **配置持久化存储**
   - 使用外部存储卷
   - 定期备份数据

4. **监控和告警**
   - 集成 Prometheus + Grafana
   - 配置关键指标告警

5. **安全配置**
   - 启用 ACL 权限控制
   - 配置 SSL/TLS 加密

## 📚 参考资料

- [RocketMQ 官方文档](https://rocketmq.apache.org/docs/quick-start/)
- [RocketMQ GitHub](https://github.com/apache/rocketmq)
- [RocketMQ Spring Boot Starter](https://github.com/apache/rocketmq-spring)
- [RocketMQ Dashboard](https://github.com/apache/rocketmq-dashboard)

## 📞 技术支持

如有问题，请查看：
1. 容器日志：`docker logs <container-name>`
2. Dashboard 监控面板
3. RocketMQ 官方文档

---

**版本信息**
- RocketMQ: 5.1.4
- Dashboard: 1.0.0
- Docker Compose: 3.9

