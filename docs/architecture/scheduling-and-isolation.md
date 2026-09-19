# 定时任务与消费者隔离

## 1. 定时任务

定时任务部署在 **flash-admin** 模块，由管理端统一调度。

| 任务 | 位置 | 频率 | 说明 |
|------|------|------|------|
| `FlashSaleScheduler` | flash-admin | 每 60 秒 | 自动将到达开始时间的 PENDING 活动激活，将超过结束时间的 ACTIVE 活动结束。激活时按 `DB stock − 在途` **SETNX 补建**库存状态键，键已存在则完全不覆盖 |
| `OrderScheduler` | flash-admin | 每 300 秒 | 自动取消超过 15 分钟未支付的订单，归还 DB 库存后调用 `stock_restore.lua mode=0` 归还 Redis 库存与限购计数（只改已存在的键） |

### FlashSaleScheduler — 活动状态流转

- **频率**：每 60 秒执行一次
- **PENDING → ACTIVE**：`startTime <= 当前时间` 的活动，更新状态为 ACTIVE，并确保 Redis 库存状态键就绪 —— 键不存在时按 `DB stock − 在途` 用 SETNX 补建，已存在则完全不覆盖
- **ACTIVE → ENDED**：`endTime <= 当前时间` 的活动，更新状态为 ENDED

### OrderScheduler — 超时订单取消

- **频率**：每 5 分钟执行一次
- 查询状态为 PENDING 且创建时间超过 15 分钟的订单
- 将这些订单状态更新为 CANCELLED
- 回滚数据库库存（`restoreStock`）
- 回滚 Redis 状态：单次 `stock_restore.lua mode=0` 调用同时完成库存 +1 与限购计数 −1，且只对已存在的键生效（避免为早已结束的场次建出无 TTL 的脏键）
- 归还脚本的 Redis 异常被 `FlashStockState.apply()` 吞掉，因此上面那个 `@Transactional` 照常提交：最坏情况是 DB 已归还、Redis 少归还一次，只会保守地少卖；让异常向外抛反而会把用户的取消动作整体回滚掉

::: warning 无分布式锁
两个 `@Scheduled` 方法都没有分布式锁。多实例部署 flash-admin 时，同一批订单/活动会被每个实例各处理一次，表现为库存重复归还（`OrderScheduler` 的重复归还尤其需要关注）。单实例运行（当前部署形态）无此问题。
:::

## 2. 消费者隔离机制

### 问题背景

`flash-api` 和 `flash-admin` 两个 Spring Boot 应用的启动类均配置了：

```java
@SpringBootApplication(scanBasePackages = "com.flashsale")
```

这意味着两个应用都会扫描到 `flash-service` 模块中的 `FlashOrderConsumer`，如果不做隔离，将导致：

- **两个应用各创建一个消费者实例**，属于同一个 Consumer Group
- RocketMQ 在同一 Consumer Group 内做负载均衡，消息可能被 `flash-admin` 消费
- `flash-admin` 是管理后台，不应承担订单处理职责，且其环境配置（线程池、连接数等）可能不适合高并发消费

### 解决方案：@ConditionalOnProperty

```java
@Component
@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true")
public class FlashOrderConsumer {
    // ...
}
```

### 配置差异

| 应用 | 配置项 | 值 | 消费者是否创建 |
|------|--------|----|----------------|
| flash-api | `flash.flash.consumer.enabled` | `true` | 是 — 负责消费订单消息 |
| flash-admin | `flash.flash.consumer.enabled` | `false` | 否 — 不创建消费者 Bean |

这样保证了只有 `flash-api` 实例（可水平扩展）消费秒杀订单消息，`flash-admin` 专注于管理功能和定时任务调度，两者职责清晰、互不干扰。

消费者处理流程细节见 [RocketMQ 消息机制](../development/rocketmq.md)。
