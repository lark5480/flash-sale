# Redis Lua 脚本

两处 Lua 都在 Redis 服务端单线程执行，保证原子性。库存 / 限购 / 在途三个状态键的语义与 TTL 规则见[数据设计](../architecture/data-design.md)。

## 1. 库存预扣脚本（`stock_deduct.lua`）

位于 `flash-service/src/main/resources/scripts/stock_deduct.lua`。

**参数说明：**

| 参数 | 含义 | 取值 |
|------|------|------|
| KEYS[1] | 库存 Key | `flash:stock:{flashSaleId}` |
| KEYS[2] | 用户已购计数 Key | `flash:user:purchased:{flashSaleId}:{userId}` |
| KEYS[3] | 在途预扣计数 Key | `flash:inflight:{flashSaleId}` |
| ARGV[1] | 每人限购数量 | `flash_sale.limit_per_user` |
| ARGV[2] | 已购计数 TTL（秒） | `RedisConstants.stockTtlSeconds(endTime)` |
| ARGV[3] | 在途计数 TTL（秒） | `RedisConstants.stockTtlSeconds(endTime)` |

**返回值：**

| 返回值 | 含义 |
|--------|------|
| `1` | 购买成功：库存 -1，用户计数 +1，在途计数 +1 |
| `0` | 超过用户限购次数 |
| `-1` | 库存不足（已售罄）**或库存 Key 不存在** |

**执行逻辑：**

```lua
-- 1. 检查用户是否已超过限购次数
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')
if purchased >= tonumber(ARGV[1]) then
    return 0
end

-- 2. 检查库存是否充足（Key 缺失按售罄处理，禁止在此脚本内回源 DB）
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    return -1
end

-- 3. 原子操作：扣库存 + 记录用户购买 + 累加在途预扣
redis.call('DECR', KEYS[1])

-- TTL 只在首次购买时设置：每次重设会让计数键随购买滑动
local count = redis.call('INCR', KEYS[2])
if count == 1 then
    redis.call('EXPIRE', KEYS[2], ARGV[2])
end

-- 在途 = 已从 Redis 预扣但 DB 尚未落库的量，是 DB 重建库存的校正依据
local inflight = redis.call('INCR', KEYS[3])
if inflight == 1 then
    redis.call('EXPIRE', KEYS[3], ARGV[3])
end

return 1
```

> 库存 Key 缺失时脚本直接返回 `-1` 而不回源 DB：回源逻辑留在 Java 侧的 `FlashStockState.ensureStockKey()`，它按 `DB stock − 在途` 用 `SETNX` 补建，键已存在时完全不覆盖。

## 2. 库存归还脚本（`stock_restore.lua`）

与预扣反向，同一个脚本按 `ARGV[1]` 的 mode 区分三种语义：

| mode | 场景 | 库存 | 已购计数 | 在途计数 |
|------|------|------|----------|----------|
| `1` | MQ 发送失败，回滚本次预扣 | +1 | -1 | -1 |
| `0` | 超时取消 / 退款，归还已落库订单 | +1 | -1 | 不变 |
| `2` | 消费者到达业务终态，只收敛在途计数 | 不变 | 不变 | -1 |

**关键约束：只对「已存在」的 Key 生效。** 裸 `INCR`/`DECR` 会为早已结束的场次建出无 TTL 的脏键，库存键还会被凭空 +1，下一次场次误读到这个脏值。返回值为实际改动的键数量，`0` 表示本场状态键已全部过期。

## 3. MQ 发送失败必须回滚 Redis

Lua 预扣成功后 Redis 状态已变更（库存 -1、已购 +1、在途 +1），如果后续 RocketMQ 同步发送失败，**必须回滚**，否则这部分库存既落不到 DB 也回不到 Redis：

- **回滚实现**：`FlashOrderServiceImpl` 捕获发送异常后调用 `FlashStockState.rollbackReservation()`，即 `EVAL stock_restore.lua mode=1`，三个键的增减在 Redis 服务端一次完成
- **回滚失败只记日志**：`FlashStockState.apply()` 吞掉 Redis 异常。此时在途计数会残留偏高，下一次库存重建因此少放库存 —— 偏高的代价是少卖，可接受；反向（凭空放出库存）不可接受。同一策略也用于取消 / 退款的归还：归还失败不回滚外层 `@Transactional` 的 DB 落库，避免一次 Redis 抖动把用户已成功的取消操作一起撤掉
- **关键原则**：Redis 预扣是"乐观"操作，MQ 发送失败时不能假设 Redis 状态一定正确

> 这两个脚本用 Testcontainers 起真实 Redis 验证语义（`StockScriptRedisIntegrationTest`），见[测试](./testing.md)。
