# ⚡ SeckillBuy-Market — 高并发秒杀系统（300 TPS 实战级）  
> 🌐 校园电商场景｜金融级库存一致性｜可水平扩展的分布式秒杀中台  
> 🔗 [线上压测报告（JMeter 300 TPS）](./docs/jmeter-report.pdf) | 🐙 [GitHub 仓库](https://github.com/fw-coder-tl/seckillbuy-market)  

[![Java 17](https://img.shields.io/badge/Java-17-blue?logo=java)](https://openjdk.org/)  
[![Spring Boot 3.x](https://img.shields.io/badge/Spring_Boot-3.2-green?logo=spring)](https://spring.io/projects/spring-boot)  
[![Redis](https://img.shields.io/badge/Redis-7.2-purple?logo=redis)](https://redis.io/)  
[![RocketMQ](https://img.shields.io/badge/RocketMQ-5.1.4-orange?logo=apache)](https://rocketmq.apache.org/)  
[![MySQL Sharding](https://img.shields.io/badge/Sharding-JDBC-5.3.2-red?logo=mysql)](https://shardingsphere.apache.org/)  

---

## 🚀 一句话定位  
这是一个**真实落地、经 JMeter 压测验证达 300+ TPS 的秒杀系统**，聚焦三大核心挑战：  
🔹 **零超卖**（Redis预扣减 + MQ异步落库 + MySQL乐观锁三重保障）  
🔹 **低延迟**（商品查询 <200ms｜下单链路平均耗时 186ms）  
🔹 **高可用**（库存分桶 + 规则引擎动态编排 + XXL-JOB分片关单）  

> 💡 不是 Demo，不是“Hello World”，而是**面向真实流量设计的交易级中间件能力沉淀**。

---

## 🌈 快速预览（3秒建立认知）
![SeckillBuy-Market 秒杀演示 GIF](./docs/seckill-demo.gif)  
> ✅ 模拟 500 用户并发抢购｜实时显示库存变化｜下单成功弹窗 + 订单号生成  

---

## 🧱 系统架构图（分层解耦 · 可演进）
![SeckillBuy-Market 架构图](./docs/architecture.png)  
*核心分层说明：*  
- **接入层**：Nginx 负载均衡 + 静态资源 CDN；前端 Vue3 + Token 防刷（限流+验证码）  
- **网关层**：Spring Cloud Gateway → 统一鉴权 / 接口熔断 / 请求染色（TraceID）  
- **业务层**：  
  - `SeckillService`：Redis Lua 脚本原子扣减（防穿透+防重放）  
  - `OrderRuleEngine`：基于 Drools 的规则引擎 → 动态组装「限购策略」「用户等级折扣」「地域白名单」  
- **异步解耦层**：RocketMQ → 解耦下单与库存扣减/订单生成/短信通知  
- **数据层**：  
  - MySQL 5.7（ShardingSphere 分库分表：按 `user_id % 4` 分4库，`order_id % 8` 分8表）  
  - Redis Cluster（热商品库存缓存 + 用户秒杀资格令牌）  
  - Elasticsearch（订单搜索 & 运营看板）  

---

## 📈 性能实测数据（JMeter 300 TPS 压测结果）
| 指标 | 数值 | 说明 |
|------|------|------|
| ✅ **最大吞吐量** | **312 TPS** | 500 并发线程下稳定达成（持续5分钟） |
| ✅ **平均响应时间** | **186 ms** | 下单接口（含Redis+MQ+DB写入） |
| ✅ **商品查询耗时** | **<200 ms** | 本地缓存 + Redis二级缓存 + MySQL主从读写分离 |
| ✅ **超卖率** | **0%** | 全链路校验：Redis预占 → MQ幂等 → DB乐观锁 → 补单对账 |
| ✅ **关单成功率** | **99.98%** | XXL-JOB 分片任务 + 本地线程池兜底 |

> 📊 报告详情见：[`./docs/jmeter-report.pdf`](./docs/jmeter-report.pdf)（含响应时间分布图、错误率曲线、TPS趋势）

---

## 🛠️ 核心技术亮点（不止于“用了Redis”）
| 方向 | 实现方案 | 为什么关键？ |
|------|-----------|----------------|
| **库存防超卖** | 🔹 Redis Lua 原子脚本（预扣减 + token生成）<br>🔹 RocketMQ 事务消息（确保扣减与订单创建强一致）<br>🔹 MySQL 乐观锁 + 版本号机制（最终落库兜底） | ✅ 三道防线覆盖「缓存击穿」「网络分区」「DB长事务」全部风险场景 |
| **高性能商品查询** | 🔹 Caffeine 本地缓存（热点商品TTL=10s）<br>🔹 Redis 缓存穿透防护（空值缓存+布隆过滤器）<br>🔹 MySQL 主从分离 + 读写分离中间件 | ✅ 查询 P99 < 190ms，支撑首页千级QPS |
| **灵活规则编排** | 🔹 Drools 规则引擎封装 `OrderRuleService`<br>🔹 YAML 配置即生效（无需重启）<br>🔹 支持「限购数量」「时段限购」「会员等级加购」组合策略 | ✅ 运营同学可自助配置促销规则，研发0介入 |
| **可靠关单服务** | 🔹 XXL-JOB 分片任务（每台机器处理 `user_id % N` 的未支付订单）<br>🔹 本地线程池兜底（防止调度中心宕机） | ✅ 关单任务 100% 执行，失败自动重试 + 告警钉钉通知 |

---

## 🚪 快速启动（本地体验）
```bash
# 1. 克隆项目
git clone https://github.com/fw-coder-tl/seckillbuy-market.git  
cd seckillbuy-market  

# 2. 启动依赖（推荐 Docker Compose）
docker-compose -f docker-compose-env.yml up -d  

# 3. 修改配置（application-prod.yml）
#    - Redis 地址 / RocketMQ namesrv / MySQL 分库连接串  
#    - 开启 `seckill.enable=true`  

# 4. 启动服务
./mvnw spring-boot:run -Pprod  
