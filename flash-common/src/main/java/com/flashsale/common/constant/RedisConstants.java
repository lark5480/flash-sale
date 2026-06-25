package com.flashsale.common.constant;

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

    /** 商品缓存 Key 前缀，格式：item:{itemId} */
    public static final String ITEM_CACHE_KEY = "item:";

    // ==================== 过期时间（秒） ====================

    /** 秒杀缓存默认 TTL：秒杀活动结束后 1 小时自动过期 */
    public static final long FLASH_CACHE_TTL = 3600L;

    /** 商品缓存 TTL：24 小时过期 */
    public static final long ITEM_CACHE_TTL = 86400L;

    /** 验证码 Redis Key 前缀，格式：captcha:{captchaId} */
    public static final String CAPTCHA_KEY = "captcha:";

    /** 验证码过期时间（秒）：5 分钟 */
    public static final long CAPTCHA_TTL = 300L;
}
