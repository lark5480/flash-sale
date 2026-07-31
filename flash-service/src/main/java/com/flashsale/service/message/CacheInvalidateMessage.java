package com.flashsale.service.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Redis Pub/Sub 缓存失效消息体
 *
 * <p>用于跨节点广播 Caffeine 缓存失效事件，
 * 各节点收到消息后 invalidate 本地 Caffeine 缓存，保证多节点数据一致性。</p>
 *
 * <p>不可变设计：仅通过构造器赋值，移除 setter，线程安全。</p>
 *
 * @author flash-sale
 */
public class CacheInvalidateMessage {

    // ===== 缓存名称常量 =====

    /** 秒杀详情缓存名称 */
    public static final String CACHE_FLASH_SALE_DETAIL = "flashSaleDetail";

    /** 活跃秒杀活动列表缓存名称 */
    public static final String CACHE_ACTIVE_FLASH_SALE = "activeFlashSale";

    /** 商品缓存名称 */
    public static final String CACHE_ITEM = "item";

    /** 全量失效标识：key 为此值时调用 invalidateAll() */
    public static final String KEY_ALL = "ALL";

    // ===== 消息字段 =====

    /** 缓存名称，标识哪个 Caffeine Cache */
    private final String cacheName;

    /** 要失效的缓存 key；值为 {@link #KEY_ALL} 时表示调用 invalidateAll() */
    private final String key;

    /** 消息来源节点 ID，用于过滤自身发布的消息避免冗余处理 */
    private final String sourceNodeId;

    /**
     * Jackson 反序列化专用无参构造器（private）
     */
    @SuppressWarnings("unused")
    private CacheInvalidateMessage() {
        this.cacheName = null;
        this.key = null;
        this.sourceNodeId = null;
    }

    @JsonCreator
    public CacheInvalidateMessage(@JsonProperty("cacheName") String cacheName,
                                  @JsonProperty("key") String key,
                                  @JsonProperty("sourceNodeId") String sourceNodeId) {
        this.cacheName = cacheName;
        this.key = key;
        this.sourceNodeId = sourceNodeId;
    }

    public String getCacheName() {
        return cacheName;
    }

    public String getKey() {
        return key;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }
}
