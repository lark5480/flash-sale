-- ============================================================
-- 秒杀库存原子归还 Lua 脚本
-- 与 stock_deduct.lua 反向：把预扣的库存与限购计数还给 Redis
-- ============================================================
--
-- KEYS[1] = flash:stock:{flashSaleId}          -- 库存 Key
-- KEYS[2] = flash:user:purchased:{flashSaleId}:{userId}  -- 用户已购计数 Key
-- KEYS[3] = flash:inflight:{flashSaleId}        -- 在途预扣计数 Key
-- ARGV[1] = mode
--   1 = 回滚一次预扣：库存 +1、限购 -1、在途 -1（MQ 发送失败，消息从未进入队列）
--   0 = 归还一笔已落库订单：库存 +1、限购 -1，不动在途（超时取消 / 退款）
--   2 = 只收敛在途计数：不动库存与限购（消费者到达业务终态，
--       那部分库存已由 deductStock 记到 DB，Redis 库存键此时不该加回来）
--
-- 关键约束：只对「已存在」的 Key 生效。
-- 裸 INCR/DECR 会为早已结束的场次建出无 TTL 的 Key，库存键还会被凭空 +1，
-- 下一次场次误读到这个脏值。键不存在说明本场 Redis 状态已过期，无需归还。
--
-- 返回值：实际改动的 Key 数量（0~3），0 表示本场状态键已全部过期
-- ============================================================

local changed = 0

if ARGV[1] ~= '2' and redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
    changed = changed + 1
end

if ARGV[1] ~= '2' and redis.call('EXISTS', KEYS[2]) == 1 then
    if tonumber(redis.call('GET', KEYS[2]) or '0') > 0 then
        redis.call('DECR', KEYS[2])
        changed = changed + 1
    end
end

if ARGV[1] ~= '0' and redis.call('EXISTS', KEYS[3]) == 1 then
    if tonumber(redis.call('GET', KEYS[3]) or '0') > 0 then
        redis.call('DECR', KEYS[3])
        changed = changed + 1
    end
end

return changed
