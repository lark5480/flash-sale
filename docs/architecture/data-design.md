# 数据设计（DB + Redis Key）

## 1. 数据库设计

共 4 张核心表：

| 表名 | 说明 | 关键索引 |
|------|------|----------|
| `user` | 用户表：用户名、密码(BCrypt)、角色 | `username` 唯一索引 — 登录查询 |
| `item` | 商品表：名称、描述、原价、图片 | 无特殊索引，数据量小，全表扫描可接受 |
| `flash_sale` | 秒杀活动表：关联商品、秒杀价、总库存、可用库存、开始/结束时间、状态 | `status` 索引 — 定时任务按状态批量查询；`item_id` 索引 — 按商品查活动 |
| `flash_order` | 秒杀订单表：关联用户和活动、订单号、数量、状态、messageKey | `message_key` 唯一索引 — 幂等校验与轮询查询；`user_id + flash_sale_id` 联合索引 — 用户订单查询；`status + create_time` 联合索引 — 超时订单清理 |

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

Lua 脚本细节见 [Redis Lua 脚本](../development/redis-lua.md)。
