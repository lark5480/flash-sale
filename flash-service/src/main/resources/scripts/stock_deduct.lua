-- ============================================================
-- 秒杀库存原子扣减 Lua 脚本
-- 在 Redis 服务端单线程执行，保证原子性
-- ============================================================
--
-- KEYS[1] = flash:stock:{flashSaleId}          -- 库存 Key
-- KEYS[2] = flash:user:purchased:{flashSaleId}:{userId}  -- 用户已购计数 Key
-- ARGV[1] = limitPerUser                        -- 每人限购数量
-- ARGV[2] = ttlSeconds                          -- 用户购买记录的过期时间（秒）
--
-- 返回值说明:
--   1  = 购买成功（库存-1，用户计数+1）
--   0  = 超过用户限购次数
--  -1  = 库存不足（已售罄）
-- ============================================================

-- 1. 检查用户是否已超过限购次数
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')
if purchased >= tonumber(ARGV[1]) then
    return 0
end

-- 2. 检查库存是否充足
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    return -1
end

-- 3. 原子操作：扣库存 + 记录用户购买 + 设置过期
redis.call('DECR', KEYS[1])
redis.call('INCR', KEYS[2])
redis.call('EXPIRE', KEYS[2], ARGV[2])

return 1
