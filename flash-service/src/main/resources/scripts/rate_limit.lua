-- Redis 滑动窗口限流
-- KEYS[1]: rate limit key
-- ARGV[1]: window start timestamp (ms)
-- ARGV[2]: current timestamp (ms)
-- ARGV[3]: TTL (seconds)
-- 返回当前窗口内请求数（不含本次），由调用方判断是否超限

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
local current = redis.call('ZCARD', KEYS[1])
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]) + 1)
return current
