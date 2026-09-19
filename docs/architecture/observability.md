# 可观测性（监控栈）

项目采用 **Spring Boot Actuator → Micrometer → Prometheus → Grafana** 标准监控链路，配合 Sentinel Dashboard 实现熔断降级。

> 本页是监控与可观测性的**唯一详细出处**（原 `01-架构文档 §10` 与 `03-开发文档 §10` 重复，已合并至此）。

## 1. 监控架构

```
应用（flash-api / flash-admin）
  ↓ 暴露 /actuator/prometheus 端点
Prometheus (:9090) ← 每 15s 拉取（Pull）
  ↓ 存储时序数据
Grafana (:3000) ← 查询 + 可视化大盘

Sentinel Dashboard (:8718) ← 动态推送流控/熔断规则
  ↓ AOP 环绕
FlashOrderServiceImpl.purchase()
```

## 2. 核心组件

| 组件 | 端口 | 地址 | 职责 |
|------|------|------|------|
| Prometheus | 9090 | http://localhost:9090 | 拉取 + 存储指标，PromQL 查询 |
| Grafana | 3000 | http://localhost:3000（admin/admin） | 可视化大盘，连接 Prometheus 数据源 |
| Sentinel Dashboard | 8718 | http://localhost:8718（sentinel/sentinel） | 动态推送流控/熔断规则，实时监控 |
| Node Exporter | 9100 | — | 暴露宿主机 CPU/内存/磁盘指标 |

## 3. 启动监控栈

```bash
# 启动全部监控服务
docker compose up -d prometheus grafana node-exporter sentinel-dashboard

# 或和中间件一起启动
docker compose up -d mysql redis nacos rocketmq-namesrv rocketmq-broker prometheus grafana node-exporter sentinel-dashboard
```

## 4. 关键配置

**application.yml（flash-api / flash-admin 通用）**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,env,beans
  metrics:
    tags:
      application: ${spring.application.name}  # 区分 flash-api / flash-admin
    distribution:
      # ★ 关键：暴露 histogram bucket，否则 P99 计算为 "No data"
      percentiles-histogram:
        http.server.requests: true
```

::: danger 两个必须记住的坑
1. **P99 依赖 `_bucket`**：P99 分位数计算依赖 `_bucket` 指标，Spring Boot 默认只暴露 `_count` / `_sum`。不配 `percentiles-histogram: true` 会导致 Grafana P99 面板 "No data"。自定义 Timer 必须加 `.publishPercentileHistogram()`。埋点在 Controller 层（用户调一次统计一次），不在 Consumer 层。
2. **抓取地址随部署方式切换（两者互斥）**：后端跑宿主机时 `docker/prometheus/prometheus.yml` targets 指向 `host.docker.internal:8081/8082/8080`；改全容器部署时须换回容器名 `api:8081` / `admin:8082` / `gateway:8080`。改完**必须** `docker restart flash-prometheus`——挂载的配置不热加载，否则 Targets 停在旧配置（表现为大盘全部 No data）。详见[全量容器化部署](../deployment/full-container.md)。
:::

## 5. 自定义业务指标

`FlashSaleMetrics`（`flash-service/src/main/java/com/flashsale/service/metrics/FlashSaleMetrics.java`）。

::: warning 查询时用右列的 Prometheus 指标名
Micrometer 的点分名在暴露时转成下划线，并按类型加后缀：Counter → `_total`，Timer → `_seconds`（展开为 `_bucket` / `_count` / `_sum`），Gauge 无后缀。用点分名查询会报 `parse error: unexpected character: '.'`。
:::

| 代码注册名（Micrometer） | Prometheus 指标名（查询 / 配面板用） | 类型 | tag | 说明 |
|---|---|---|---|---|
| `flashsale.order.success` | `flashsale_order_success_total` | Counter | — | 下单成功次数（订单真正落库） |
| `flashsale.order.fail` | `flashsale_order_fail_total` | Counter | `reason` | 下单失败次数，按失败原因归因 |
| `flashsale.order.duration` | `flashsale_order_duration_seconds_bucket` | Timer | — | 下单接口耗时（SLO 分桶 10ms/50ms/100ms/500ms/1s/5s + 百分位直方图） |
| `flashsale.stock.deduct.duration` | `flashsale_stock_deduct_duration_seconds_bucket` | Timer | — | Redis Lua 预扣库存耗时 |
| `flashsale.stock.rebuild` | `flashsale_stock_rebuild_total` | Counter | — | 库存键重建次数，非零说明 Redis 库存状态曾丢失 |
| `flashsale.stock.remaining` | `flashsale_stock_remaining` | Gauge | `flashSaleId` | Redis 剩余可用库存（20s 采样） |
| `flashsale.stock.inflight` | `flashsale_stock_inflight` | Gauge | `flashSaleId` | 在途预扣数（已预扣未落库），持续不为零说明消费端堵塞 |
| `flashsale.stock.drift` | `flashsale_stock_drift` | Gauge | `flashSaleId` | 库存漂移 = DB 库存 −（Redis 库存 + 在途），正常恒为 0，非零即库存泄漏 |
| `flashsale.mq.send` | `flashsale_mq_send_total` | Counter | `result` | MQ 发送结果：success / fail |
| `flashsale.mq.send.duration` | `flashsale_mq_send_duration_seconds_bucket` | Timer | — | MQ 同步发送耗时 |
| `flashsale.mq.consume` | `flashsale_mq_consume_total` | Counter | `result` | 消费结果分布：created / duplicate / business_terminal / system_retry / dead_letter |
| `flashsale.mq.consume.duration` | `flashsale_mq_consume_duration_seconds_bucket` | Timer | — | 消息消费处理耗时 |
| `flashsale.order.settle.latency` | `flashsale_order_settle_latency_seconds_bucket` | Timer | — | 下单消息发出到订单落库的端到端延迟 |
| `flashsale.metrics.sample.error` | `flashsale_metrics_sample_error_total` | Counter | — | 库存指标采样失败次数，非零说明采样时 Redis/DB 访问异常 |

Timer 类的 `_bucket` 用于算分位数（`histogram_quantile`），`_count` / `_sum` 用于算平均耗时。

`reason` tag 取值（`OrderFailReason`）：`not_found`、`not_started`、`ended`、`sold_out`、`repeat`、`rate_limited`、`mq_send_error`、`business_terminal`、`system_error`、`dead_letter`。其中 `sold_out` / `repeat` / `business_terminal` 属于正常业务终态，`mq_send_error` / `system_error` / `dead_letter` 才是需要告警的故障。

**埋点位置：**
- `FlashOrderController.purchase()`：下单接口总耗时（用户调一次统计一次，在 Controller 层而非 Consumer 层）
- `FlashOrderServiceImpl.purchase()`：失败原因归因、Redis Lua 预扣耗时、MQ 发送失败
- `FlashOrderProducer`：发送成功/失败与耗时
- `FlashOrderConsumer`：消费结果分布、消费耗时、端到端延迟
- `FlashOrderDeadLetterConsumer`：死信计数

库存类 Gauge 由 `FlashStockMetricsSampler` 每 20s 采样一次，采用「定时采样 + Gauge 读内存」形态：Prometheus 抓取路径上不访问 Redis，避免 Redis 抖动拖垮 `/actuator/prometheus`，代价是指标有 20s 级延迟。该采样器仅在开启 `@EnableScheduling` 的应用（flash-admin）执行。

**聚合查询需自行 sum**（指标带 tag）：

```text
# 下单失败原因分布
sum by (reason) (rate(flashsale_order_fail_total[5m]))

# 下单成功率
sum(rate(flashsale_order_success_total[1m]))
  / (sum(rate(flashsale_order_success_total[1m])) + sum(rate(flashsale_order_fail_total[1m])))

# 库存漂移告警（正常恒为 0）
flashsale_stock_drift != 0
```

## 6. Grafana 看板

Dashboard JSON 通过 **Provisioning 自动加载**（版本控制，重启不丢失）：

```
docker/grafana/
├── provisioning/
│   ├── datasources/prometheus.yml    # 自动注册 Prometheus 数据源
│   └── dashboards/dashboards.yml     # Dashboard Provider 配置
└── dashboards/
    └── flash-sale-overview.json      # 看板定义（JVM / HTTP / 业务指标）
```

看板包含 4 行：Overview（6 个 stat 卡片）→ Business Metrics → HTTP Metrics → JVM & System。

## 7. Sentinel 熔断降级 {#sentinel-熔断降级}

`FlashOrderServiceImpl.purchase()` 标注 `@SentinelResource`：

```java
@SentinelResource(
    value = "flashSale_purchase",
    blockHandler = "purchaseBlock",   // 流控/熔断时调用
    fallback = "purchaseFallback"   // 业务异常时调用
)
```

**与 @RateLimit 的区别**：

| 维度 | @RateLimit | Sentinel |
|------|-----------|----------|
| 作用层 | 控制层（Web MVC Interceptor） | 业务层（AOP 环绕 Service 方法） |
| 实现 | Redis ZSET 滑动窗口 | Sentinel 核心引擎 |
| 用途 | 防刷 / 防撞库 | 流量控制 + 熔断降级 |

**熔断触发验证**：Dashboard 实时监控看熔断状态（CLOSED → OPEN）；触发时请求不走 `purchase()` 方法体，直接进 `purchaseBlock()`。

## 8. 常见坑

| 现象 | 原因 | 解决 |
|------|------|------|
| Grafana 面板 "No data" | Prometheus Targets DOWN / 指标不存在 / 时间范围不对 | 按顺序排查：targets → graph → actuator → 时间范围 |
| P99 面板 "No data" | 缺少 `_bucket` 指标 | 配 `percentiles-histogram: true` |
| Prometheus 拉不到本地应用 | 容器内 localhost 指向容器自身 | targets 默认已指向 host.docker.internal:8081/8082（宿主机模式）；后端全容器部署时改回容器名 api:8081, admin:8082 |
| Grafana "Failed to upgrade legacy queries" | Dashboard JSON 用旧 `rows` 格式 | 重写为扁平 `panels` 格式 |
| Sentinel 规则重启丢失 | 默认内存存储 | 生产环境接 Nacos 持久化 |
