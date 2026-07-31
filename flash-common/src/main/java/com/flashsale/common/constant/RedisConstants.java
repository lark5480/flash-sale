package com.flashsale.common.constant;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis Key 常量 — 秒杀业务专用
 *
 * @author flash-sale
 */
public final class RedisConstants {

    private RedisConstants() {
    }

    // ==================== Key 前缀 ====================

    /** 秒杀库存余量 Key 前缀，格式：flash:stock:{flashSaleId} */
    public static final String FLASH_STOCK_KEY = "flash:stock:";

    /** 秒杀活动详情缓存 Key 前缀，格式：flash:sale:{flashSaleId} */
    public static final String FLASH_SALE_KEY = "flash:sale:";

    /** 用户已购数量 Key 前缀，格式：flash:user:purchased:{flashSaleId}:{userId} */
    public static final String FLASH_USER_PURCHASED_KEY = "flash:user:purchased:";

    /** Redisson 分布式锁 Key 前缀 */
    public static final String FLASH_LOCK_KEY = "flash:lock:";

    /** 活跃秒杀活动列表缓存 Key */
    public static final String ACTIVE_FLASH_SALE_LIST_KEY = "active:list";

    /** 商品缓存 Key 前缀，格式：item:{itemId} */
    public static final String ITEM_CACHE_KEY = "item:";

    // ==================== Pub/Sub 频道 ====================

    /** Redis Pub/Sub 缓存失效频道 */
    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";

    // ==================== 过期时间（秒） ====================

    /** 秒杀缓存默认 TTL：秒杀活动结束后 1 小时过期 */
    public static final long FLASH_CACHE_TTL = 3600L;

    /** 商品缓存 TTL：24 小时过期 */
    public static final long ITEM_CACHE_TTL = 86400L;

    /** 验证码 Redis Key 前缀，格式：captcha:{captchaId} */
    public static final String CAPTCHA_KEY = "captcha:";

    /** 验证码过期时间（秒）：5 分钟 */
    public static final long CAPTCHA_TTL = 300L;

    /** 缓存空值标记：用于缓存穿透防护，表示 DB 中不存在该数据 */
    public static final String CACHE_NULL = "@@NULL@@";

    /** 空值缓存短 TTL（秒）：空值 5 分钟后过期 */
    public static final long NULL_CACHE_TTL = 300L;

    // ==================== 工具方法 ====================

    /**
     * 在基础 TTL 上增加 ±300 秒的随机偏移，防止缓存雪崩
     *
     * @param baseTtl 基础 TTL（秒）
     * @return 带随机偏移的 TTL（秒），最小为 1
     */
    public static long randomTtl(long baseTtl) {
        long offset = ThreadLocalRandom.current().nextLong(-300, 301);
        long ttl = baseTtl + offset;
        return ttl > 0 ? ttl : 1;
    }
}
