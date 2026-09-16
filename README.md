# mall-seckill 高并发秒杀系统

按《高并发秒杀系统开发文档 v1.0》从零实现的高并发秒杀系统：**Redis Lua 原子预减库存 + RabbitMQ 异步削峰 + MySQL 乐观锁兜底 + 唯一索引幂等 + 应用内令牌桶限流 + 隐藏秒杀地址**。

- 技术栈：Spring Boot 3.2.5（Java 17）/ MyBatis-Plus / MySQL 8.0 / Redis 7 / RabbitMQ 3.12 / Nginx 1.25
- 里程碑：M1 基础 CRUD + 库存预减 → M2 秒杀接口 + 防超卖 → M3 MQ 异步削峰 → M4 限流 + 隐藏地址 + 部署骨架
- 验收证据：11 个自动化测试全绿（含 5000 并发防超卖、2000 并发削峰）、JMeter 压测数据见下文

## 目录结构

```
mall-seckill/
├── src/main/java/com/example/seckill/
│   ├── controller/   # 秒杀/订单/管理接口
│   ├── service/      # 秒杀、库存、订单、令牌桶限流
│   ├── mq/           # 生产者/消费者（RabbitMQ 异步下单）
│   ├── redis/        # Redis 操作 + Lua 脚本（库存预减/令牌桶）
│   ├── task/         # 订单过期扫描（30s）
│   ├── config/       # RabbitMQ 声明、指标
│   └── util/         # 雪花 ID、错误码
├── src/main/resources/application.yml
├── sql/init.sql      # 建库建表（DDL 见开发文档第 5 节）
├── docker-compose.yml  # MySQL/Redis/RabbitMQ/App/Nginx 一键起
├── Dockerfile
├── nginx/nginx.conf    # 入口限流 20r/s burst=40（文档 7.7）
└── src/test/           # 11 个自动化测试
```

## 快速启动（本机）

前置：JDK 17+、MySQL 8（库 `mall_seckill`）、Redis、RabbitMQ（用户 `seckill/seckill123`）。

```bash
# 1. 初始化数据库（MySQL 8）
mysql -uroot -p < sql/init.sql

# 2. 启动中间件（确保 3306/6379/5672 可用）

# 3. 打包并启动应用（端口 8080）
mvn -B -DskipTests package
java -jar target/mall-seckill-1.0.0.jar

# 4. 验收测试（11 个用例，含 5000 并发防超卖 / 2000 并发削峰）
mvn -B test
```

## 快速启动（Docker Compose）

```bash
docker compose up -d --build   # 自动拉起 MySQL/Redis/RabbitMQ/App/Nginx
curl http://127.0.0.1:8080/actuator/health
```

## 核心接口

统一前缀 `/api/seckill/v1`，业务错误码 10001~10007（详见开发文档第 6 节）。

| 接口 | 说明 |
|---|---|
| `POST /api/admin/seckill/activities` | 创建秒杀场次（含场次号、时间段、商品、库存） |
| `POST /api/admin/seckill/activities/{id}/preheat` | 预热：Redis 写库存 + 生成活动令牌桶 |
| `GET /api/seckill/v1/address/{activityId}` | 获取隐藏秒杀地址（6 位随机串，TTL 7200s；未开始 10003 / 已结束 10004） |
| `POST /api/seckill/v1/{secretPath}/order` | 秒杀抢购（校验隐藏地址；未开始 10003、限流 10005、地址无效 10006、售罄 10001） |
| `GET /api/seckill/v1/order/result?orderNo=` | 异步下单结果轮询（成功/失败） |
| `POST /api/seckill/v1/order/pay` | 支付（2 分钟内有效） |

## 限流设计（M4）

1. **Nginx 层**（`nginx/nginx.conf`）：`limit_req zone=seckill_zone:20m rate=20r/s` + `burst=40 nodelay`，按客户端 IP 整机限流，超限返回 503。
2. **应用层**（Redis Lua 令牌桶，活动维度）：`seckill:rate:{activityId}` 存令牌数/上次补充时间戳，容量与速率由 `seckill.rate-limit.capacity / rate-per-second` 配置（默认 100/100），超限返回 10005。压测/防超卖测试中可关闭（`seckill.rate-limit.enabled=false`）。
3. **隐藏地址**：秒杀地址不暴露真实 activityId，下单须携带 `GET /address/{activityId}` 下发的 6 位随机串（存 `seckill:address:{activityId}`，TTL 7200s），不匹配返回 10006。

## 防超卖与幂等（M2/M3）

- 入口：Redis Lua 脚本原子执行「库存>0 扣减 + 已购标记」，防超卖与一人多单。
- 落库：消费者 DB 唯一索引（`uk_order_no` / `uk_user_activity`）幂等兜底，消息重投/业务重复不产生重复订单；乐观锁 `stock` 表版本号兜底 Redis 与 DB 一致。
- 削峰：入口只做 Redis 预减 + 发 MQ，数据库写入由消费者异步完成（默认 prefetch=50、concurrency=5~10）。

## 测试与压测结果

### 自动化测试（`mvn test`，11/11 通过）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| SeckillConcurrencyTest | 1 | 5000 线程抢 10 件库存：Lua 扣减恰 10 次、Redis 余量 0、DB 订单恰 10 条 |
| PeakingTest | 1 | 2000 并发削峰：入口 1000 成功、DB 最终 1000、峰值约 361 条/s、削峰约 4:1 |
| AddressRateLimitTest | 3 | 令牌桶限流、隐藏地址读写、超频返回 10005 |
| StockServiceTest | 2 | 库存预减/回补、乐观锁扣减 |
| OrderExpireTaskTest | 1 | 订单 2 分钟过期扫描回补库存 |
| RedisLockUtilTest | 3 | 分布式锁获取/释放/续期 |

### JMeter 压测（文档 10.3，实测于 2026-09-16）

环境：Windows 单机 12 逻辑核，JMeter 5.6.3 与应用/MySQL/Redis/RabbitMQ 同机；压测入口极限时关闭应用内令牌桶（`--seckill.rate-limit.enabled=false`），Nginx 层限流配置见 `nginx/nginx.conf`。测试计划 `jmeter/*.jmx`，原始结果 `jmeter/result-*.jtl`、HTML 报告 `jmeter/report-*/`。

| 场景 | 并发模型 | 请求数 | QPS | P50 | P99 | 错误率 | 超卖/重复 |
|---|---|---|---|---|---|---|---|
| 洪峰（10.3） | 5000 线程 × 2 轮，ramp=0，10 库存 | 10000 | 1356 | — | — | 21.8%（单机 TCP backlog 连接拒绝） | **0 / 0**，订单恰 10 |
| 稳态·低载 | 200 线程 × 50 轮 | 10000 | 1878（稳态段约 3168） | 21ms | 48ms（稳态段 10ms） | **0%** | 0 / 0 |
| 稳态·长测 | 200 线程 × 100 轮 | 20000 | 2492 | 52ms | 221ms | 1.2%（客户端端口耗尽） | — |
| 中载 | 400 线程 × 25 轮 | 10000 | 2109 | 108ms | 293ms | 1.7% | — |
| 高载 | 800 线程 × 50 轮 | 40000 | 2978 | 217ms | 584ms | 47.0%（端口耗尽/连接拒绝） | — |

结论：
- **正确性达标**：5000×2 洪峰下超卖 0、重复订单 0、Redis 扣减数=订单数=10（DB 唯一索引 + Lua 原子扣减双重保证）。
- **延迟达标**：200 并发 0 错误下 P99=48ms、稳态段 P99=10ms（优于 P99<100ms 目标）；入口已对活动时间窗做 Redis 缓存，售罄/重复请求不查 DB。
- **削峰达标**：PeakingTest（2000 并发 / 1000 库存）DB 峰值写入约 361 条/s、削峰约 4:1，远低于 1000 条/s 上限。
- **吞吐说明**：单机同机压测稳态约 2500~3200 QPS，未达 5000 示例目标；应用单请求稳态 P50 约 6ms，瓶颈在压测端（JMeter 同机抢 12 核 CPU、Windows 临时端口 TIME_WAIT 耗尽出现 `BindException`、瞬时 backlog 连接拒绝），非应用处理能力。压测机与被测机分离或分布式压测可消除；生产由 Nginx `limit_req` 削峰 + 应用多实例水平扩展达到整机目标。

复现压测：
```bash
# 1. 启动应用（压测入口极限时关闭应用内限流）
java -jar target/mall-seckill-1.0.0.jar --seckill.rate-limit.enabled=false
# 2. 创建场次(10 库存) → preheat → GET /address/{id} 拿 secretPath，填入 jmx 变量
# 3. 洪峰场景（文档 10.3）
jmeter -n -t jmeter/seckill.jmx -l jmeter/result.jtl -e -o jmeter/report
# 4. 正确性核查（应恰为 10，无重复）
mysql -uroot -e "SELECT COUNT(*),COUNT(DISTINCT order_no),COUNT(DISTINCT user_id)
  FROM mall_seckill.seckill_order WHERE activity_id=<id>;"
```
