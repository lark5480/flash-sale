# 秒杀下单核心链路

> 这是整个系统最关键的业务链路，请重点理解。

```mermaid
sequenceDiagram
    participant C as 客户端
    participant GW as Gateway
    participant API as flash-api
    participant Svc as FlashOrderService
    participant Redis as Redis
    participant MQ as RocketMQ
    participant Consumer as FlashOrderConsumer
    participant DB as MySQL

    C->>GW: POST /api/flash-sale/{id}/purchase
    GW->>GW: AuthGlobalFilter 校验 JWT
    GW->>API: 转发请求 (Authorization + X-User-Id/X-User-Role)
    API->>Svc: purchase(flashSaleId, userId)

    Note over Svc,DB: Step 1: 活动校验
    Svc->>DB: 查询 FlashSale 活动
    DB-->>Svc: 返回活动数据
    Svc->>Svc: 校验：状态=ACTIVE，当前时间在 startTime~endTime 范围内

    Note over Svc,Redis: Step 2: 库存状态键就绪（仅缺失时补建）
    Svc->>Redis: EXISTS flash:stock:{flashSaleId}
    alt 库存 Key 不存在
        Svc->>Redis: GET flash:inflight:{flashSaleId}
        Svc->>Redis: SETNX flash:stock:{flashSaleId} = DB stock - 在途
        Note right of Redis: 键已存在时绝不覆盖：<br/>它是业务状态不是缓存
    end

    Note over Svc,Redis: Step 3: Lua 原子扣减
    Svc->>Redis: EVAL stock_deduct.lua (KEYS: stock / user:purchased / inflight)
    Note right of Redis: 原子操作：<br/>1. 检查用户购买次数是否超限<br/>2. 检查库存是否 > 0（Key 缺失即售罄）<br/>3. DECR stock<br/>4. INCR user:purchased 计数<br/>5. INCR inflight 在途计数
    Redis-->>Svc: 返回扣减结果

    alt 扣减失败（库存不足 / 超限购）
        Svc-->>API: 返回错误
        API-->>C: 抢购失败
    end

    Note over Svc,MQ: Step 4: 发送 MQ 消息
    Svc->>Svc: 生成 messageKey (UUID)
    Svc->>MQ: syncSend(topic, messageKey, payload)
    MQ-->>Svc: 发送成功
    alt 发送失败
        Svc->>Redis: EVAL stock_restore.lua mode=1（库存+1 限购-1 在途-1）
        Svc-->>API: 抛出下单失败
    end

    Svc-->>API: 返回 messageKey
    API-->>C: 返回 messageKey（客户端开始轮询）

    Note over Consumer,DB: Step 5: 异步消费（削峰）
    MQ->>Consumer: 推送消息
    Consumer->>Redis: GET flash:msg:result:{messageKey} (终态判定)
    alt 已是 DONE / FAILED
        Consumer-->>MQ: ACK（跳过，收敛已由写入标记的那次投递完成）
    end
    Consumer->>DB: selectByMessageKey（标记过期时的幂等兜底）
    alt 订单已存在
        Consumer->>Redis: SETNX result = DONE（只补标记，不再递减在途）
        Consumer-->>MQ: ACK
    end
    Consumer->>Redis: Redisson lock(flash:lock:{flashSaleId}, 10s)
    Consumer->>DB: 限购兜底 COUNT（status NOT IN 已取消/已退款）
    Consumer->>DB: deductStock (乐观锁: stock > 0)
    Consumer->>DB: INSERT flash_order
    Consumer->>Redis: SETNX result = DONE，成功则 DECR flash:inflight
    Consumer-->>MQ: ACK
    Note over Consumer,Redis: 业务异常(售罄/超限购) → SETNX result = FAILED + 递减在途，且不重试<br/>系统异常 → 不写标记，抛出交给 RocketMQ 重试<br/>重试耗尽 → 死信消费者同样走 SETNX + 递减在途

    Note over C,Redis: Step 6: 客户端轮询结果
    loop 轮询直到 DONE / FAILED 或超时
        C->>API: GET /api/order/status?messageKey=
        API->>Redis: GET flash:msg:result:{messageKey}
        API-->>C: 返回状态（PROCESSING / DONE / FAILED + failReason）
    end
```

## 关键设计要点

1. **Lua 脚本保证原子性** — 库存扣减、用户购买计数与在途计数在单次 Redis 调用中原子完成，避免竞态条件。脚本细节见 [Redis Lua 脚本](../development/redis-lua.md)。
2. **同步发送 + 异步消费** — `syncSend` 确保消息到达 Broker，消费者异步处理实现削峰。
3. **messageKey 轮询机制** — 客户端拿到 messageKey 后轮询 Redis 中的处理状态，实现异步转同步的用户体验。
4. **多重幂等保障** — Redis 终态标记 SETNX（消息级）→ DB messageKey 查询（标记过期后兜底）→ `message_key` UNIQUE 索引（并发级）→ Redisson 分布式锁 + DB 乐观锁（数据级）。
5. **库存键是业务状态，不是缓存** — `flash:stock:{id}` 只由 `FlashStockState` 读写：缺失时按 `DB stock − flash:inflight:{id}` 用 SETNX 补建，已存在时任何路径（含管理端更新、缓存失效）都不得覆盖或删除。**唯一例外是管理端把非活跃场次重新激活**：上一周期的键仍持有旧 DB stock 算出的值，若直接沿用则停售期间调大的库存永不生效，因此激活前先删除旧键，再由 `warmUpRedis` 按新 `DB stock − 在途` 重建（非活跃状态无购买流量，删除是安全的）。在途计数由预扣 Lua `+1`、由终态标记的 SETNX 胜出者 `-1`，保证每笔预扣恰好收敛一次；多减会放出虚假库存（超卖方向），少减只会保守地少卖。
