-- ============================================================
-- 秒杀库存原子扣减 Lua 脚本
-- 在 Redis 服务端单线程执行，保证原子性
-- ============================================================
--
-- KEYS[1] = flash:stock:{flashSaleId}          -- 库存 Key
-- KEYS[2] = flash:user:purchased:{flashSaleId}:{userId}  -- 用户已购计数 Key
-- KEYS[3] = flash:inflight:{flashSaleId}        -- 在途预扣计数 Key
-- ARGV[1] = limitPerUser                        -- 每人限购数量
-- ARGV[2] = userKeyTtlSeconds                   -- 已购计数 TTL（活动结束 + 宽限）
-- ARGV[3] = inflightTtlSeconds                  -- 在途计数 TTL（活动结束 + 宽限）
--
-- 返回值说明:
--   1  = 购买成功（库存-1，用户计数+1，在途计数+1）
--   0  = 超过用户限购次数
--  -1  = 库存不足（已售罄）或库存 Key 不存在
-- ============================================================

-- 1. 检查用户是否已超过限购次数
--    ARGV[1] <= 0 或无法解析为数字时视为不限购，与 DB 兜底
--    FlashOrderServiceImpl.checkPurchaseLimit 的口径保持一致：
--    否则把 0/负数限购解析成「purchased(0) >= 0 恒成立」，每笔购买都会被拒
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')
local limit = tonumber(ARGV[1])
if limit ~= nil and limit > 0 and purchased >= limit then
    return 0
end

-- 2. 检查库存是否充足（Key 缺失按售罄处理，禁止在此脚本内回源 DB）
local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
if stock <= 0 then
    return -1
end

-- 3. 原子操作：扣库存 + 记录用户购买 + 累加在途预扣
redis.call('DECR', KEYS[1])

-- TTL 只在首次购买时设置：每次重设会让计数键随购买滑动，
-- 短 TTL 更会让限购在活动结束前就失效
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
