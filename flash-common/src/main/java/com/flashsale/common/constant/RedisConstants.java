package com.flashsale.common.constant;

import java.time.Duration;
import java.time.LocalDateTime;
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

    /**
     * 在途预扣计数 Key 前缀，格式：flash:inflight:{flashSaleId}
     * <p>
     * Redis 已预扣但 MQ 消息尚未落库的数量。DB 的 stock 天然滞后这个数量，
     * 库存 Key 丢失后按 DB 重建时必须先减去它，否则会把在途量当成可用库存放出。
     */
    public static final String FLASH_INFLIGHT_KEY = "flash:inflight:";

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

    /** 秒杀详情/列表缓存默认 TTL：1 小时（库存类状态键不用它，见 stockTtlSeconds） */
    public static final long FLASH_CACHE_TTL = 3600L;

    /**
     * 库存类状态键在活动结束后的保留时长（秒）：1 天
     * <p>
     * 结束后仍会有超时取消、退款归还和客户端轮询落到这些键上，
     * 键提前消失会让归还侧建出无 TTL 的脏键。
     */
    public static final long FLASH_STOCK_GRACE_SECONDS = 86400L;

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

    /**
     * 计算场次状态键（库存 / 限购计数 / 在途计数）的 TTL：距活动结束的剩余时间 + 宽限期
     * <p>
     * 这些键不是普通缓存而是业务状态，TTL 必须覆盖整个场次。用固定 TTL 会让跨小时的
     * 场次在进行中自然过期，之后的重建只能拿滞后的 DB 值兜底，从而放出虚假库存。
     * 已结束或 endTime 为空的场次退化为「只剩宽限期」，仍能承接迟到的取消与归还。
     *
     * @param endTime 活动结束时间，可为 null
     * @return TTL 秒数，最小为宽限期
     */
    public static long stockTtlSeconds(LocalDateTime endTime) {
        if (endTime == null) {
            return FLASH_STOCK_GRACE_SECONDS;
        }
        long remain = Duration.between(LocalDateTime.now(), endTime).getSeconds();
        return Math.max(remain, 0L) + FLASH_STOCK_GRACE_SECONDS;
    }
}
