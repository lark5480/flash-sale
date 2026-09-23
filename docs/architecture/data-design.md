# 数据设计（DB + Redis Key）

## 1. 数据库设计

共 4 张核心表：

| 表名 | 说明 | 关键索引 |
|------|------|----------|
| `user` | 用户表：用户名、密码(BCrypt)、角色 | `username` 唯一索引 — 登录查询 |
| `item` | 商品表：名称、描述、原价、图片 | `idx_name`、`idx_status`；数据量小，实际以主键与状态过滤为主 |
| `flash_sale` | 秒杀活动表：关联商品、秒杀价、总库存、可用库存、开始/结束时间、状态 | `idx_status` — 定时任务按状态批量查询；`idx_item_id` — 按商品查活动；`idx_start_time` / `idx_end_time` — 状态流转按时间窗筛选 |
| `flash_order` | 秒杀订单表：关联用户和活动、订单号、数量、状态、messageKey | `uk_message_key` 唯一索引 — 幂等校验与轮询查询（终极兜底）；`idx_user_flash`（`user_id, flash_sale_id`）联合索引 — 用户订单查询与限购 DB 兜底；`idx_status`、`idx_user_id` 单列索引 |

> `OrderScheduler` 的超时扫描按 `status + create_time` 过滤，而建表只有单列 `idx_status`（**没有** `(status, create_time)` 复合索引）。当前数据量下走 `idx_status` 足够，订单表涨起来后这是第一个要补的索引。

## 2. Redis Key 设计

| Key 格式 | 用途 | TTL |
|----------|------|-----|
| `flash:stock:{flashSaleId}` | 秒杀库存计数器（Lua 脚本原子操作，**业务状态非缓存**） | `stockTtlSeconds` = 剩余场次 + 86400s |
| `flash:inflight:{flashSaleId}` | 在途预扣计数：Redis 已扣、DB 未落库的量，DB 重建库存键时的校正依据 | `stockTtlSeconds` = 剩余场次 + 86400s |
| `flash:user:purchased:{flashSaleId}:{userId}` | 用户购买次数计数，防止超限购（DB 另有 `countQuotaOccupied` 兜底） | `stockTtlSeconds` = 剩余场次 + 86400s |
| `flash:sale:{flashSaleId}` | 活动详情缓存（不含实时库存，见下方展示口径），减少 DB 查询 | 3600s ± 300s |
| `flash:lock:{flashSaleId}` | Redisson 分布式锁，保证消费者同一活动串行处理库存 | 锁自动续期（watchdog） |
| `flash:msg:result:{messageKey}` | MQ 消息处理结果标记，值为 DONE/FAILED，仅在业务终态后由 SETNX 写入 | `MSG_RESULT_TTL`（3600s） |
| `rate:limit:{key}:{userId\|ip:xxx}` | 接口限流滑动窗口（ZSET） | window + 1s |
| `captcha:{captchaId}` | 算术验证码答案 | `CAPTCHA_TTL`（300s） |
| `active:list` | 进行中的秒杀活动列表缓存（L2） | `randomTtl(30)` = 30s ± 300s |
| `item:{itemId}` | 商品详情缓存（L2） | `randomTtl(86400)` = 86400s ± 300s |
| `cache:invalidate` | **Pub/Sub 频道，不是键**：L1 Caffeine 的跨节点失效广播，见 §3 | 无（消息即发即弃） |

> 注：`randomTtl()`（±300s 防雪崩）只用于纯缓存键。库存 / 限购 / 在途三个状态键的 TTL 由场次结束时间决定并额外保留 1 天宽限期 ——
> 固定 TTL 会让跨小时的场次在进行中自然过期，之后只能拿滞后的 DB 值重建，从而放出虚假库存。
> `flash:msg:result` 的 3600s 短于状态键生命周期，因此消费者侧仍保留 DB messageKey 幂等查询作为标记过期后的兜底。
>
> **展示库存口径**：`flash:sale:{id}` 与 `active:list` 是某一时刻的 VO 快照，而下单只扣 `flash:stock:{id}` 与 DB，
> 从不回写或失效这两个快照（秒杀下逐单失效热点缓存等于打穿缓存）。因此进行中场次对外展示的库存一律由
> `FlashSaleServiceImpl.applyRealtimeStock()` 用 `flash:stock:{id}` 覆盖快照值；非进行中场次没有购买流量，
> DB `stock` 才是权威（管理员可在停售期补货），保持快照值不变。

::: warning 已知缺陷
`randomTtl()` 的偏移固定为 ±300s，`active:list` 用 `randomTtl(30)` 时负偏移会被钳到 1s，约一半的写入只能得到 1s TTL，等于这一层 L2 缓存在大部分时间不生效。偏移量应按基数的比例取值。（`RedisConstantsTest` 已把这个现状显式钉住防止悄悄回退。）
:::

## 3. 三级缓存的跨节点失效（Redis Pub/Sub）

L1 Caffeine 是**进程内**缓存，写请求只失效自己所在节点的 L1，多实例部署时其他节点会继续用各自的旧快照。为此加了一条 Redis Pub/Sub 广播链路：

| 角色 | 类 | 说明 |
|------|-----|------|
| 发布 | `CacheInvalidatePublisher.publish(cacheName, key)` | 写操作在**本地**失效之后调用，向 `cache:invalidate` 频道发一条 JSON |
| 消息体 | `CacheInvalidateMessage` | `{cacheName, key, sourceNodeId}`，不可变；`key = "ALL"` 表示 `invalidateAll()` |
| 订阅 | `RedisPubSubConfig` + `CacheInvalidateListener` | `RedisMessageListenerContainer` 把监听器绑到该频道，收到后按 `cacheName` 失效对应的 Caffeine 实例 |
| 身份 | `sourceNodeId` = `${spring.application.name}-${server.port}` | 监听器**跳过自己发的消息**，避免冗余处理 |

三个 `cacheName` 与 L1 实例的对应关系（`CacheConfig`）：`flashSaleDetail`（60s / maxSize 500）、`activeFlashSale`（120s / 10）、`item`（120s / 1000）。

实际发布点只有两处：

- `FlashSaleServiceImpl.evictCache()` → `detail:{saleId}` 精确失效 + `activeFlashSale` 全量失效
- `ItemServiceImpl.evictCache()` → `item:{itemId}` 精确失效

::: warning 边界：这是「尽力而为」，不是可靠投递
1. Pub/Sub 无持久化，**当时离线或订阅抖动的节点收不到**，不补发。兜底手段是 L1 那 60-120s 的短 TTL —— 广播只负责把不一致窗口从「TTL 级」压到「毫秒级」，压不到零。
2. 发布失败只 `log.warn`（`CacheInvalidatePublisher` 内部吞异常），不影响主事务；监听侧解析/失效失败只 `log.error`。
3. **广播只管 L1。** L2 Redis 是共享实例，纯缓存键（`flash:sale:{id}`、`active:list`、`item:{id}`）由写节点在 `evictCache()` 里自己删一次就够，不需要广播。而 `flash:stock:{id}` / `flash:inflight:{id}` / `flash:user:purchased:{id}:{uid}` 三个**状态键任何路径都不删**——被误删就会由滞后的 DB 重建并放出虚假库存（口径见 §2 与[秒杀下单核心链路](./flash-sale-flow.md)要点 5）。
4. 监听器与配置都在 `flash-service`，而 api / admin 两个应用都 `scanBasePackages = "com.flashsale"`，因此**两边都会订阅**该频道。
:::

Lua 脚本细节见 [Redis Lua 脚本](../development/redis-lua.md)。
