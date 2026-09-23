# Flash Sale 秒杀系统 — 知识笔记与面试 Q&A

> 基于项目实际代码实现整理，涵盖缓存、消息队列、分布式锁、限流、认证、数据库、架构设计、压测等核心知识点。

---
GitHub:[lark5480/flash-sale: 高并发秒杀系统 — Spring Cloud 微服务架构，Redis Lua 原子扣库存 + RocketMQ 异步削峰 + Sentinel 熔断降级 + Prometheus/Grafana 监控](https://github.com/lark5480/flash-sale)
## 一、缓存架构

### 知识概述

Flash Sale 采用 **Caffeine（L1）→ Redis（L2）→ DB（L3）** 三级缓存架构。`FlashSaleServiceImpl` 和 `ItemServiceImpl` 中通过注入 `Cache<String, String>`（Caffeine）和 `StringRedisTemplate`（Redis）实现逐级查询与回填。配合三级防护机制——空值标记防穿透、`randomTtl()` 防雪崩、Caffeine `get(key, function)` per-key 同步防击穿——保障高并发场景下的缓存稳定性。L1 是进程内缓存，多节点一致性由 **Redis Pub/Sub 广播失效**（`cache:invalidate` 频道）收敛，短 TTL 兜底。

### 面试 Q&A

**Q1: 三级缓存的一致性怎么保证？如果 Caffeine 和 Redis 数据不一致怎么办？**

**A:** 写操作同时失效两级缓存（invalidate Caffeine + delete Redis），下次读请求逐级回源并回填。**多节点场景下 L1 有跨节点失效广播**：写节点本地失效后，通过 Redis Pub/Sub 频道 `cache:invalidate` 广播 `{cacheName, key, sourceNodeId}`，其他节点的 `CacheInvalidateListener` 收到后失效各自的 Caffeine（`sourceNodeId` 用于跳过自己发的消息）。这条链路是尽力而为——Pub/Sub 不补发离线节点，所以 Caffeine 的短 TTL（60-120s）仍是最终兜底。机制、发布点与四条边界见[数据设计 §3](../architecture/data-design.md#_3-三级缓存的跨节点失效-redis-pub-sub)。

**Q2: 缓存穿透、击穿、雪崩你项目里怎么处理的？**

⚠️ 必须基于已实现的代码回答：
- 穿透：空值标记 `@@NULL@@`（`RedisConstants.CACHE_NULL`）+ 短 TTL 300s（`NULL_CACHE_TTL`），DB 查不到时缓存空值防止恶意请求穿透
- 雪崩：`randomTtl()` 使用 `ThreadLocalRandom` 在基础 TTL 上叠加 ±300s 随机偏移，避免大量缓存同时过期（⚠️ 已知缺陷：偏移是**绝对值**，套在 30s 基数上会让约一半写入被钳到 1s，`active:list` 的 L2 因此大部分时间不生效；改成按基数比例取值才能修，`RedisConstantsTest` 已把这个现状显式钉住防止悄悄回退）
- 击穿（Redis 层）：`FlashStockState.ensureStockKey()` 用 `setIfAbsent()`（SETNX）补建库存键，**键已存在时绝不覆盖**；补建口径是 `DB stock − flash:inflight`（DB 天然滞后在途量，直接按 DB 赋值就会放出虚假库存）
- 击穿（Caffeine 层）：`cache.get(key, function)` 模式 per-key 同步，同一 key 只有一个线程执行加载函数

**Q3: 活动列表为什么也走三级缓存（Caffeine → Redis → DB）？**

**A:** 活动列表读请求远大于写请求。走三级缓存后，Caffeine 命中直接返回（P50 <10ms），未命中时从 Redis 读取避免打穿到 DB。写操作时同时失效 Caffeine + Redis（`evictCache()` 中 `delete(ACTIVE_FLASH_SALE_LIST_KEY)`），保证下次读回源最新数据。数据量小（不超过 10 条），内存开销可忽略。

**Q4: `randomTtl()` 为什么用 `ThreadLocalRandom` 而不是 `Math.random()`？**

**A:** `Math.random()` 内部使用全局 `Random` 实例，高并发下存在 CAS 竞争（多线程竞争同一个 seed）。`ThreadLocalRandom` 每个线程维护独立随机数生成器，无竞争，性能更好。

---

## 二、消息队列与异步削峰

### 知识概述

秒杀下单采用 **Redis Lua 原子预扣 → RocketMQ 异步发消息 → Consumer 事务性落库** 的链路。`FlashOrderServiceImpl.purchase()` 完成 Redis 预扣后立即返回"处理中"，客户端通过 `messageKey` 轮询订单状态。`FlashOrderConsumer` 消费消息后执行 `deductStockAndCreateOrder()`，在同一事务内完成 DB 扣库存 + 创建订单。

### 面试 Q&A

**Q5: 为什么用 Redis Lua 脚本扣库存而不是直接 DECR？Lua 脚本的优势是什么？**

**A:** Lua 脚本在 Redis 单线程中原子执行，不会被其他命令插入。如果分成多次命令（先 GET 判断库存 → DECR 扣减），并发场景下两个请求可能同时读到 stock=1 然后都扣减成功，导致超卖。Lua 脚本（`stock_deduct.lua`）将「检查限购 + 检查库存 + 扣库存 + 记录用户计数」合并为一次 RTT，既保证原子性又减少网络开销。

**Q5.1: 「每人限购 N 件」是每天限购还是整场累计？**

**A:** **整场（单个 `flashSaleId`）累计，没有每日清零。** 两处判据都跟"天"无关：

- Redis 快路径键是 `flash:user:purchased:{flashSaleId}:{userId}`——键里没有日期分量，TTL 用 `RedisConstants.stockTtlSeconds(endTime)` = 场次剩余时间 + 1 天宽限，跟着**场次结束**走而不是自然日零点；
- DB 兜底 `FlashOrderMapper.countQuotaOccupied` 是 `where user_id=? and flash_sale_id=? and status NOT IN (2,3) and is_deleted=0`——**没有任何 `create_time` 条件**。

配套的三个口径：① 占用 = 待支付(0) + 已支付(1)；取消(2) 与退款(3) 释放额度，与归还脚本 `stock_restore.lua` 递减 Redis 限购计数是同一套规则（所以「库存退还了却不给买」这种自相矛盾不会出现）。② 一个用户最多同时持有 N 件有效订单，退掉可以重新抢。③ 计数单位是"件"而不是"单"其实等价——`flash_order` 没有 `quantity` 列，一次下单恒为 1 件，所以 `COUNT(1)` 就是件数。

为什么两个数可能不相等：Redis 那个键只是快路径，键会随场次 TTL 过期或被驱逐，此时归还就落不到它上面。**具体到我实测到的 3 vs 4 我没有回溯成因**（候选：键过期后重建、某次归还发生在键已不存在时、历史压测数据遗留），但结论不受影响——两侧**规则**一致，且 DB 是权威：Redis 少算导致多放行时，消费者那一层会按 DB 拒掉（表现为同步通过、异步失败），所以异步终态必须带原因回传，否则用户只看到"失败"却不知道是限购。

要真做成每日限购得动三处：Redis 键加日期分量（`...:{yyyyMMdd}`）、DB 查询加 `create_time` 范围、以及决定跨天旧键的 TTL 怎么算；同时语义上要想清楚——秒杀的限购目的通常是防黄牛囤货，而不是"每天给一次机会"，所以整场累计才是默认选择。

**Q6: RocketMQ 消息丢失怎么办？如何保证消息不丢？**

**A:** (1) 生产者端：`FlashOrderProducer` 同步发送，等待 Broker ACK，失败则回滚 Redis 预扣；(2) Broker 端：配置同步刷盘（SYNC_FLUSH）保证持久化；(3) 消费者端：消费成功才返回 ACK，系统异常 re-throw 触发重试，重试耗尽进死信队列并补写带原因的 `FAILED` 终态标记；(4) 幂等由「终态标记（只在业务终态后写）→ DB messageKey 查询 → UNIQUE 索引」三层兜住，重复投递不会重复扣库存。

**Q7: 如果 Redis 扣库存成功但 MQ 发送失败，怎么处理？**

**A:** 当前实现不再由 Java 侧裸发 `INCR/DECR`，而是统一走归还脚本：catch 块里调 `flashStockState.rollbackReservation(flashSaleId, userId)`，由 `stock_restore.lua` 的回滚模式一次性完成「库存 +1、限购计数 −1、在途计数 −1」——消息没进 MQ 就不再是在途，必须减掉，否则在途数偏高、下一次按键重建会少卖。脚本只对**已存在**的键生效（EXISTS 守卫），不会为早已结束的场次建出无 TTL 的脏键。回滚后抛 `SYSTEM_ERROR` 让客户端重试。

仍存在两个极端情况：(1) 回滚本身失败（Redis 不可用）——此时库存与在途都停在预扣值，靠「键随场次 TTL 消失 + 重建按 `DB stock − 在途` 补建」保守收敛（少卖方向，不会超卖）；(2) 想彻底消除这个窗口就用 RocketMQ 事务消息：先发半消息 → 执行 Redis 预扣 → 按结果 commit/rollback 半消息。

**Q8: 客户端轮询 messageKey 的频率怎么设计？会不会给 Redis 造成压力？**

**A:** 建议指数退避策略（500ms → 1s → 2s），最大轮询次数限制（如 30 次），超时提示用户稍后查看。可进一步改进为 WebSocket/SSE 推送。

---

## 三、分布式锁

### 知识概述

项目使用 **Redisson** 实现分布式锁，全仓只有**一处**真实用例（`grep redissonClient.getLock` 可验证）：`FlashOrderConsumer` 消费秒杀消息时以 `flash:lock:{flashSaleId}` 为锁键，串行化同一场次的并发消费，锁超时 10s 固定。另有 `FlashStockState.ensureStockKey()` 用 Redis `setIfAbsent()`（SETNX，**不是** Redisson 锁）补建库存状态键：键已存在就完全不动它，缺失时按 `DB stock − flash:inflight` 补建。

⚠️ 面试注意：不要说成"多处用分布式锁"。库存扣减的并发正确性靠的是 DB 乐观锁（`deductStock` 带 `stock > 0` 条件更新，返回 0 即视为售罄）+ Redis Lua 原子预扣 + 场次状态机，而不是 Redisson。

### 面试 Q&A

**Q9: Redisson 分布式锁的底层原理是什么？watchdog 机制了解吗？**

**A:** Redisson 使用 Redis HASH + Lua 脚本实现可重入锁。lock 时：key 不存在则 HSET + EXPIRE；已存在且 field 是当前线程则重入计数+1；否则 BLPOP 订阅解锁事件。watchdog 默认 30s，每 10s 续期一次。本项目 `FlashOrderConsumer` 中 `lock.lock(10, TimeUnit.SECONDS)` 设置 10s 固定超时，关闭了 watchdog。

**Q10: 为什么 Consumer 需要分布式锁？Redis Lua 不是已经扣过库存了吗？**

**A:** Redis Lua 是「乐观预扣」，只保证 Redis 层面不超卖。多个消费者可能同时消费同一活动的不同消息，并发写 DB 时需要分布式锁串行化，配合 DB 乐观锁（`WHERE stock > 0`）双重保障。

---

## 四、限流

### 知识概述

项目实现了基于 **Redis ZSET 滑动窗口** 的接口限流机制。通过自定义 `@RateLimit` 注解标注在 Controller 方法上，`RateLimitInterceptor` 拦截器在请求进入时执行 `rate_limit.lua` Lua 脚本，以时间戳为 score 进行窗口内请求统计。限流主体支持按 userId（已认证）或客户端 IP（未认证）两种维度。

### 面试 Q&A

**Q11: 滑动窗口和固定窗口限流的区别？为什么选滑动窗口？**

**A:** 固定窗口在窗口边界可能出现 2 倍突发流量。滑动窗口以当前时间为终点回溯，粒度更细。Redis ZSET 以时间戳为 score，`ZREMRANGEBYSCORE` 清理窗口外数据，`ZCARD` 统计窗口内请求数。

**Q19: `@RateLimit` 注解的限流粒度是怎么设计的？已认证和未认证用户有区别吗？**

**A:** `RateLimitInterceptor.resolveSubject()` 方法决定限流主体维度：已认证用户从 `SecurityContextHolder` 获取 `userId` 作为限流 key（`rate:limit:{key}:{userId}`），未认证用户降级为客户端 IP（`rate:limit:{key}:ip:{clientIp}`）。客户端 IP 优先从 `X-Forwarded-For` → `X-Real-IP` 反向代理头获取，兜底使用 `request.getRemoteAddr()`。`@RateLimit` 注解支持配置 `key`（限流维度）、`permits`（窗口内最大请求数，默认 10）、`windowSeconds`（时间窗口，默认 1s）、`message`（超限提示）。未标注 `@RateLimit` 的接口直接放行，不影响正常请求。

**Q20: `rate_limit.lua` 脚本的执行流程是什么？为什么不直接用 Java 代码操作 Redis？**

**A:** `rate_limit.lua` 执行四步操作：(1) `ZREMRANGEBYSCORE` 清理 score 在 `[0, windowStart]` 范围内的过期记录；(2) `ZCARD` 统计当前窗口内请求数并返回；(3) `ZADD` 以当前时间戳为 score 和 member 写入新记录；(4) `EXPIRE` 设置 key 过期时间为 `windowSeconds + 1` 秒，防止冷 key 堆积。返回值为本次请求前的窗口计数，由 Java 端 `RateLimitInterceptor` 判断 `count >= permits` 则返回 429。使用 Lua 脚本而非 Java 多次调用 Redis 的原因：保证清理 + 统计 + 写入 + 设置过期四步操作在 Redis 单线程中原子执行，避免并发请求在清理和写入之间插入导致计数不准，同时将四次网络 RTT 合并为一次。

---

## 五、JWT 认证

### 知识概述

项目采用 **JWT 双 Token** 认证方案，accessToken 短有效期（30min）+ refreshToken 长有效期（7d）。`AuthGlobalFilter`（Gateway 全局过滤器）做第一道鉴权：请求进入网关先**统一剥离**客户端伪造的同名 `X-User-Id`/`X-User-Role` 头，校验 Token 通过后再按 JWT payload 中的 `userId`/`role` **重写**这两个头转发给下游（该头仅作辅助，非信任边界）。Service 层 Spring Security 的 `JwtAuthenticationFilter` 做第二道防线——重新解析 `Authorization` 中的 Token 构建 `Authentication`（principal = userId），Controller 从 `Authentication` 取当前用户而非 `X-User-Id` 头。

### 面试 Q&A

**Q12: 为什么用双 Token？单 Token 不行吗？**

**A:** 单 Token 短有效期则用户频繁重新登录，长有效期则被盗用风险大。双 Token：accessToken 短有效期（30min）减少被盗风险，refreshToken 长有效期（7d）用于无感续期。

**Q13: Gateway 鉴权和 Service 层 Security 是什么关系？**

**A:** Gateway 做第一道鉴权（校验 Token + 注入用户信息），flash-api 的 `ApiSecurityConfig` 做第二道防线——**它有一份自己的 `permitAll` 清单，必须与网关白名单同步维护**：只改网关会出现「网关放行、api 仍拒 403」的裂口（游客打不开 C 端首页与详情就是这么来的，纯代码审查看不出来，端到端实跑才暴露）。按 HTTP 方法区分策略——GET 请求放行读接口前缀（`GET_SKIP_PREFIXES`），写接口强制认证；精确路径匹配（`SKIP_PATHS`）覆盖登录/注册/验证码等公开端点。返回码口径：未登录 **401**、权限不足 **403**（默认 `Http403ForbiddenEntryPoint` 会把两者一律压成 403，前端分不清该跳登录还是没权限，因此显式配置了 entry point 与 access denied handler）。绕过 Gateway 直接访问 Service 时，`JwtAuthenticationFilter` 仍会校验 `Authorization` 头里的 Token——下游不读 `X-User-Id`。

**Q21: `AuthGlobalFilter` 的白名单策略是怎么设计的？GET 和 POST 请求的放行规则有什么不同？**

**A:** `AuthGlobalFilter` 实现了三层白名单匹配：(1) **精确路径匹配** `SKIP_PATHS`——`/api/auth/register`、`/api/auth/login`、`/api/auth/refresh`、`/api/auth/captcha`、`/admin/auth/login`、`/admin/auth/captcha`、`/api/flash-sale/active` 等公开端点，所有 HTTP 方法均放行；(2) **前缀匹配** `SKIP_PREFIXES`——`/images/` 开头的静态资源请求放行；(3) **GET 方法限定前缀匹配** `GET_SKIP_PREFIXES`——`/api/flash-sale/` 前缀仅在 GET 请求时放行（秒杀详情等只读查询），POST/PUT/DELETE 等写操作仍需认证。匹配顺序为精确路径 → 前缀 → GET 前缀，任一命中即跳过鉴权。未命中任何白名单的请求必须携带 `Authorization: Bearer <token>` 头，Gateway 校验 Token 未过期后提取 `userId` 和 `role` 注入 `X-User-Id`、`X-User-Role` 请求头传递给下游微服务。

补一个安全细节（这是修过的洞）：`X-User-Id` / `X-User-Role` **不是信任边界**——客户端本来可以自带同名头伪造身份。现在过滤器在进入白名单判断**之前**就对**所有**请求统一剥离这两个头，鉴权通过后才按 JWT 重写，因此放行的公开路径干脆不携带任何身份头，下游看到的值一定由网关写入。即便如此下游仍不读这两个头，一律重新解析 Token。

---

## 六、数据库

### 面试 Q&A

**Q14: 订单表为什么用雪花 ID 做主键而不用自增？**

**A:** (1) 自增 ID 可预测，安全隐患；(2) 分布式环境多实例 ID 冲突；(3) 雪花 ID 全局唯一、趋势递增（InnoDB B+ 树友好）。JacksonConfig 将 Long 序列化为 String，解决 JS Number 精度丢失（安全整数范围 2^53）。

**Q15: deductStock 的乐观锁是怎么实现的？**

**A:** `UPDATE flash_sale SET stock = stock - 1 WHERE id = #{id} AND stock > 0 AND status = 1 AND is_deleted = 0`。SQL 原子性 + `stock > 0` 条件保证不超卖。并发时 MySQL 行锁串行化，第一个成功后 stock 减为 0，后续 WHERE 不满足返回 `updated=0`。

---

## 七、架构设计

### 面试 Q&A

**Q16: 为什么要做消费者隔离？不隔离会怎样？**

**A:** flash-api 和 flash-admin 都扫描 `com.flashsale` 包，不隔离则两个应用各创建 `FlashOrderConsumer` 实例，同属一个 Consumer Group，消息可能被 flash-admin 消费。使用 `@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true")` 确保只有 flash-api 创建消费者 Bean。

**Q17: 你在项目中实际做了哪些生产级改进？为什么选这 4 项？**

**A:** 实际做了 4 项，按优先级排序：

| # | 改进 | 为什么做 | 为什么不做另外 4 项 |
|---|---|---|---|
| 1 | **RocketMQ 同步刷盘 + 死信队列** | 下单是资金相关，消息不能丢；压测显示下单场景 500 并发出现 500 错误，消息可靠性是首要风险 | — |
| 2 | **Actuator + Prometheus + Grafana** | 微服务必备可观测性；Actuator 开箱即用，Prometheus+Grafana docker-compose 一键拉起，学习成本低 | — |
| 3 | **Sentinel 熔断降级** | 与现有 @RateLimit 形成对比面试题；Sentinel Dashboard 动态规则推送，学习价值高 | — |
| 4 | **GitHub Actions CI** | 零成本（GitHub 免费），简历能写"CI 自动构建" | — |
| — | ~~Redis 哨兵/集群~~ | — | 单机 Redis 足够，哨兵/集群是运维负担，学习价值低 |
| — | ~~Seata 分布式事务~~ | — | Redis Lua 原子 + 本地事务已够用，Seata 引入新复杂度 |
| — | ~~CDN / Nginx 静态资源~~ | — | Nginx 已有，CDN 要钱，纯运维层面 |
| — | ~~分库分表 / 读写分离~~ | — | **秒杀系统设计目标是不让流量到 DB**，分库分表是反模式 |
| — | ~~灰度发布~~ | — | K8s 层面，非当前架构 |

**核心判断**：个人学习项目的价值不是"堆中间件数量"，而是"每个技术选型的 why 能讲清楚"。面试时能说出"为什么不做"比"我做了"更显架构师判断力。

**Q22: `FlashOrderConsumer` 的三层幂等保障是怎么设计的？每层分别解决什么问题？**

**A:** 三层从快到慢，但**关键点是「标记只能在业务终态之后写」**，顺序错了就会丢单：

1. **Redis 终态标记**——`GET flash:msg:result:{messageKey}`，已是 `DONE` / `FAILED:原因` 则直接跳过重复投递。注意它**不是**入口前置的「已处理」标记：曾经写成 `onMessage()` 一进来就 `setIfAbsent(MSG_PROCESSED_KEY, "1")`，看起来更省，实际是致命 bug——系统异常时消息并没落库，标记却已写进去，RocketMQ 的 3 次重试全部被自己写过的标记短路，消息被静默吞掉，用户钱没扣货没生成，订单永久丢失。现在的语义是「只有真正到达业务终态才留痕」，所以重试路径永远不会被自己的历史挡住。
2. **DB messageKey 查询兜底**——Redis 标记有 TTL（1 小时）且可能被内存淘汰驱逐，用 `selectByMessageKey()` 查是否已有订单；命中则只补写标记（`markSettledOnly`），不再递减在途计数，因为那笔预扣在首次投递收敛时已经减过。
3. **UNIQUE 索引终极兜底**——`flash_order.message_key` 上的唯一索引，即使前两层同时失效，INSERT 也会抛唯一约束异常，`@Transactional` 连带回滚 `deductStock`，DB 不会重复扣库存。

额外一层收益：写标记用的是 `setIfAbsent`，它的返回值同时充当**「本次投递是否负责收敛在途计数」的凭据**——只有 SETNX 成功的那次才 `DECR flash:inflight:{id}`。主消费者与死信消费者处理同一条消息时不会多减，重复投递也不会少减。多减会放出虚假库存（超卖方向，不可挽回），少减只是保守少卖（可挽回），所以判据设计上宁少不多。

**面试讲法**：三层幂等本身不难，难的是「标记什么时候写」。我会主动讲自己先写错成前置、后来靠压测/端到端跑才发现丢单——比背一个正确方案更能证明理解深度。

---

## 八、压测

### 知识概述

项目使用 **wrk** 作为压测工具，运行在 WSL2 环境中通过 `resolv.conf nameserver` 获取 Windows 宿主机 IP 实现跨环境访问。压测体系包含：`flash-sale-auth.lua` 加载 JWT Token 实现认证请求、`flash-sale-test.lua` 编排混合请求（读 + 写按比例分配）、`run-benchmark.sh` 统一调度多场景压测并输出结果报告。

### 面试 Q&A

**Q18: wrk 压测你是怎么设计的？如何保证压测结果有效？**

**A:**
- 脚本设计：`flash-sale-auth.lua` 加载 JWT Token，`flash-sale-test.lua` 编排混合请求，`run-benchmark.sh` 统一调度
- 认证绕过：Python 直接生成 JWT Token 写入 `token.txt`，避免验证码干扰
- 跨环境：WSL2 通过 `resolv.conf nameserver` 获取 Windows 宿主机 IP，避免 localhost 不通
- 5 场景覆盖：基线（10 并发）、活动列表（100 并发）、详情（100 并发）、下单（500 并发）、混合（100 并发）
- 结果：混合场景 QPS 416+，P99 <70ms；读接口 P99 <100ms
- ⚠️ 下单场景 500 并发出现 500 错误 + socket error，**下单链路是高并发瓶颈**，读链路不是

---

## 九、消息可靠性

### 知识概述

RocketMQ 消息链路采用 **同步刷盘 + 有限重试 + 死信队列** 三重保障。Broker 配置 `flushDiskType = SYNC_FLUSH` 保证消息落盘后才返回 ACK；Consumer 设置 `maxReconsumeTimes = 3` 快速重试后进死信队列；`FlashOrderDeadLetterConsumer` 监听 `%DLQ%flash-order-consumer-group` 主题，记录重试耗尽消息供人工补偿。Broker 同时启用原生 Prometheus 指标导出（端口 5557），暴露消息堆积、消费延迟、TPS 等指标。

### 面试 Q&A

**Q23: RocketMQ 同步刷盘和异步刷盘什么区别？为什么选同步刷盘？**

**A:** 异步刷盘（ASYNC_FLUSH）消息写入 PageCache 即返回 ACK，吞吐高但 Broker 宕机可能丢消息；同步刷盘（SYNC_FLUSH）等待刷盘完成才返回 ACK，吞吐下降约 30-50%，但消息不丢。**秒杀下单是资金相关场景，消息不能丢，值得牺牲吞吐换可靠性。** 压测显示下单场景 QPS 250+ 已满足个人项目需求，同步刷盘的性能损失可接受。

**Q24: maxReconsumeTimes 设成 3 是为什么？默认 16 次不够吗？**

**A:** 默认 16 次重试间隔长达数十分钟（第 16 次重试在数小时后），期间消息堆积、消费者阻塞。设 3 次快速重试后进死信队列，配合「终态标记（只在业务终态后写）→ DB messageKey 查询 → UNIQUE 索引」三层幂等，重复消费不会出问题。

**Q25: 死信队列消费者具体做什么？**

**A:** `FlashOrderDeadLetterConsumer` 监听 `%DLQ%flash-order-consumer-group`（RocketMQ 在 `maxReconsumeTimes` 耗尽后自动路由）。它做三件事，缺一不可：

1. `log.error` 记录消息全字段（messageKey / userId / flashSaleId / itemId / flashPrice），供人工补偿或接告警；
2. 记 `DEAD_LETTER` 失败指标（与 `BUSINESS_TERMINAL` 分开计数，否则真故障会被正常终态淹没）；
3. **收敛终态**：调 `flashOrderSettler.settleAndRelease(failedMarker("处理多次重试仍失败，请重新下单"))`。这一步不能省——重试耗尽同样是业务终态，那笔预扣的在途计数必须递减，否则在途数长期偏高、下一次按键重建会凭空少卖；同时不写标记的话客户端会一直轮询到 PROCESSING，永远拿不到结果。标记若已被主消费者写过，SETNX 失败，这里就不会重复递减。

**实测证据**（docker compose 起真实中间件 + 宿主机跑后端，注入一条缺 `flashPrice` 的消息撞 NOT NULL）：该消息共投递 **4 次**（初次 + 3 次重试，重试间隔 10s/30s/1m），随后路由进 `%DLQ%` 并被此消费者处理，`flash:msg:result:{messageKey}` 落成带原因的 FAILED；期间 `flash_order` 零脏行（`deductStock` 随事务回滚）、`flash:inflight` 停在 0 未被减成负数。

---

## 十、可观测性

### 知识概述

项目采用 **Spring Boot Actuator → Micrometer → Prometheus → Grafana** 标准监控链路。Actuator 暴露 `/actuator/prometheus` 端点；Micrometer 是度量门面（类似 SLF4J 之于日志），Prometheus 实现 `MeterRegistry` 将指标暴露为拉模式文本格式；Prometheus Server 拉取并存储指标；Grafana 连接 Prometheus 做可视化大盘。自定义业务指标（`FlashSaleMetrics`）通过 Micrometer API 注册，暴露下单成功/失败次数、下单耗时。

### 面试 Q&A

**Q26: Actuator 和 Micrometer 是什么关系？**

**A:** Actuator 是 Spring Boot 的监控出口，提供 HTTP 端点暴露内部状态。Micrometer 是度量门面（Facade），定义 Counter/Timer/Gauge 等指标类型，解耦应用与具体监控系统。Actuator 依赖 Micrometer，Micrometer 的 `PrometheusMeterRegistry` 将指标转换为 Prometheus 可抓取的文本格式。类比：Micrometer 之于度量 ≈ SLF4J 之于日志，Prometheus 之于监控系统 ≈ Logback 之于日志实现。

**Q27: 自定义业务指标怎么做的？埋在哪里？**

**A:** `FlashSaleMetrics` 通过构造器注入 `MeterRegistry` 注册业务指标，下单相关的三个是：`flashsale.order.success`（Counter，下单成功次数）、`flashsale.order.fail`（Counter，tag `reason` 归因）、`flashsale.order.duration`（Timer，下单处理耗时，含 `.publishPercentileHistogram()`）。**完整清单别背数字**：库存（remaining / inflight / drift / rebuild）、MQ（发送与消费计数与耗时）、端到端结算延迟、采样错误等一并注册在同一个类里，权威列表见[可观测性 §5](../architecture/observability.md#_5-自定义业务指标)——指标会加，写死条数的回答下次就对不上了。

**埋点位置**：
- `flashsale.order.success`：在 `FlashOrderConsumer` 订单创建成功时记录（真正落库成功）
- `flashsale.order.fail`：在 `FlashOrderConsumer` 失败时记录（业务异常 + 系统异常）
- `flashsale.order.duration`：在 `FlashOrderController.purchase()` 中记录端到端耗时（含网络 + Service）

**⚠️ 坑**：P99 计算依赖 `_bucket` 指标，Spring Boot 默认不暴露。需要在 `application.yml` 加 `management.metrics.distribution.percentiles-histogram.http.server.requests: true`，自定义 Timer 加 `.publishPercentileHistogram()`。否则 Grafana P99 面板显示 "No data"。

**Q28: Prometheus 和 Grafana 分别负责什么？为什么不直接用 Grafana？**

**A:** Prometheus 负责**拉取（Pull）+ 存储 + 告警**，Grafana 负责**可视化 + 仪表盘**。Prometheus 本身有简单 UI 但不适合做复杂看板，Grafana 支持更丰富的图表类型、数据源混合、权限管理。两者是互补关系：Prometheus 是时序数据库 + 告警引擎，Grafana 是可视化层。

**Q34: Grafana 看板 "No data" 怎么排查？**

**A:** 按顺序排查：(1) `http://localhost:9090/targets` 看 Targets 是否 UP；(2) `http://localhost:9090/graph` 直接跑 PromQL 看是否有数据；(3) `curl http://localhost:8081/actuator/prometheus | grep 指标名` 看 actuator 是否暴露该指标；(4) P99 类面板 No data → 检查是否配了 `percentiles-histogram: true`；(5) 检查 Grafana 时间范围是否覆盖数据时间段。

**Q35: Grafana Dashboard JSON 格式有什么变化？**

**A:** Grafana 9+ 不再支持旧的 `rows` 嵌套格式，必须用扁平 `panels` 数组，每个 panel 通过 `gridPos`（h/w/x/y）定位。旧格式导入会报 "Failed to upgrade legacy queries"。`schemaVersion` 应 ≥ 39。

**Q36: Sentinel 熔断触发了怎么确认？**

**A:** 两种方式：(1) Dashboard 实时监控看熔断状态（CLOSED → OPEN）；(2) 看日志与指标 — 熔断触发时请求不走 `purchase()` 方法体，直接进 `purchaseBlock()`，所以业务日志（如 `Redis 库存 Key SETNX`）不会出现，同时 `flashsale_order_fail_total{reason="rate_limited"}` 计数上涨。

⚠️ 别在面试里说「`curl /actuator/sentinel` 看状态码」：`management.endpoints.web.exposure.include` 只放了 `health,info,prometheus`，该端点按现配置取不到。要看熔断后的真实效果，走 Prometheus 查 `flashsale_order_fail_total` 更靠谱。

---

## 十一、熔断降级

### 知识概述

项目使用 **Sentinel** 实现业务层熔断降级，与现有 `@RateLimit` 注解形成互补：`@RateLimit` 是**控制层限流**（Web MVC Interceptor，按 userId/IP 维度），Sentinel 是**业务层流控/熔断**（AOP 环绕 `FlashOrderServiceImpl.purchase()`，支持 QPS/线程数/熔断规则）。`@SentinelResource` 注解指定 `blockHandler`（流控/熔断时降级）和 `fallback`（业务异常时兜底）。`SentinelBlockExceptionHandler` 统一处理 Sentinel 拦截的请求，返回 HTTP 429 + ResultVO JSON。

### 面试 Q&A

**Q29: @RateLimit 和 Sentinel 两种限流有什么区别？为什么两个都要？**

**A:** `@RateLimit` 是自定义 Redis ZSET 滑动窗口，作用于 **Web 控制层**（`RateLimitInterceptor` 拦截 HTTP 请求），按 userId/IP 维度限流，适合防刷/防撞库。Sentinel 作用于 **业务层**（AOP 环绕 Service 方法），支持 QPS/线程数/熔断/热点参数限流，规则可动态推送。**两者互补**：控制层挡大部分恶意请求，业务层保护核心逻辑，形成多层防护。面试回答模板："控制层限流是第一道防线，拦截非法请求；业务层熔断是最后一道防线，保护核心资源不被打垮。"

**Q30: @SentinelResource 的 blockHandler 和 fallback 有什么区别？**

**A:** `blockHandler` 处理 **Sentinel 规则触发**（流控/熔断/系统保护），参数必须包含 `BlockException`；`fallback` 处理 **业务异常**（方法抛出未捕获异常），参数为 `Throwable`。两者方法签名必须与原方法一致（同返回类型 + 同参数 + 异常参数）。

**Q31: Sentinel 规则存储在哪里？重启会丢吗？**

**A:** 当前使用 **内存存储**（Dashboard 内存），重启规则丢失。生产环境应接 **Nacos/Apollo/ZK 持久化**（Sentinel 支持 `DataSource` 扩展）。面试回答模板："个人学习项目用内存存储足够，生产环境必须接 Nacos 持久化，否则重启规则丢失等于无限流。"

---

## 十二、CI/CD

### 知识概述

项目使用 **GitHub Actions** 实现持续集成。工作流 `ci.yml` 在 push 到 master/dev 分支或 PR 到 master 时触发，步骤：checkout → 设置 JDK 21（Temurin，自动缓存 `~/.m2`）→ `mvn -B clean verify`（先跑全部后端测试再构建）→ 上传所有 `**/target/*.jar` 作为 Artifact（保留 7 天）。

### 面试 Q&A

**Q32: GitHub Actions 的 Maven 缓存怎么工作的？**

**A:** `actions/setup-java@v4` 的 `cache: maven` 参数自动缓存 `~/.m2/repository` 目录。首次构建全量下载依赖，后续构建只下载增量，构建时间从数分钟降到数十秒。缓存 key 基于 `pom.xml` 哈希，依赖变更时自动刷新缓存。

**Q33: 测试这块现在到什么程度？接下来还能改什么？**

**A:** 之前是「CI 只构建不测试（项目也没测试）」，已经补上了：后端测试覆盖到秒杀链路的不变式，CI 改成 `mvn -B clean verify`，所以新改动不带可跑测试就会直接把流水线弄红。用例数我答「以 CI 的 surefire 汇总为准」而不报具体数字——加了用例，报死的数字就是下一个错。清单与跑法见[测试](../development/testing.md#_1-测试类与覆盖的不变式)。

覆盖的是秒杀链路的不变式而不是行数：库存键存在就绝不覆盖、重建 = `DB stock − 在途`、在途读失败 fail-closed、终态标记的 SETNX 胜出者才递减在途、状态键 TTL 必须覆盖整场；两处 Lua 的语义用 **Testcontainers 起真实 `redis:7-alpine`** 跑（含 200 并发抢 50 库存恰好成功 50 单不超卖），注入 5 条脚本级回归验证测试不是空跑。

一个设计点值得讲：集成测试类标 `@Testcontainers(disabledWithoutDocker = true)`——GitHub runner 有 Docker 所以会真跑，本地没有 Docker 的机器整类跳过而不是失败。**测试基础设施不能假定环境**，否则第一次红就被人肉 `-DskipTests` 绕过。

仍未做：(1) Docker 镜像构建 job（Buildx → GHCR）；(2) 静态检查（Checkstyle / SpotBugs）；(3) api 层鉴权清单无自动化守护（与网关白名单的一致性目前只能端到端实测）；(4) MQ/DB/网关端到端没有自动化测试（本轮是手工实跑），要稳定回归得靠 Testcontainers 加 Kafka/RocketMQ 模块或起 Compose 做契约测试；(5) wrk 量级并发与多实例行为仍未验证。
