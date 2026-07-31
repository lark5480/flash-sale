package com.flashsale.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.service.message.CacheInvalidateMessage;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 缓存失效消息监听器
 *
 * <p>监听 Redis Pub/Sub 频道 {@code cache:invalidate}，收到消息后
 * invalidate 本节点对应的 Caffeine 缓存，实现跨节点缓存一致性。</p>
 *
 * <p>防循环说明：本监听器只执行 {@code cache.invalidate()}，不会再次发布消息，
 * 因此不会与 {@link CacheInvalidatePublisher} 形成无限循环。</p>
 *
 * <p>自收过滤：通过 sourceNodeId 判断消息来源，跳过自身节点发布的消息，避免冗余处理。</p>
 *
 * @author flash-sale
 */
@Component
public class CacheInvalidateListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidateListener.class);

    private final Cache<String, String> flashSaleDetailCache;
    private final Cache<String, String> activeFlashSaleCache;
    private final Cache<String, String> itemCache;
    private final ObjectMapper objectMapper;
    private final String selfNodeId;

    public CacheInvalidateListener(@Qualifier("flashSaleDetailCache") Cache<String, String> flashSaleDetailCache,
                                   @Qualifier("activeFlashSaleCache") Cache<String, String> activeFlashSaleCache,
                                   @Qualifier("itemCache") Cache<String, String> itemCache,
                                   ObjectMapper objectMapper,
                                   @Value("${spring.application.name:unknown}-${server.port:0}") String selfNodeId) {
        this.flashSaleDetailCache = flashSaleDetailCache;
        this.activeFlashSaleCache = activeFlashSaleCache;
        this.itemCache = itemCache;
        this.objectMapper = objectMapper;
        this.selfNodeId = selfNodeId;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            CacheInvalidateMessage invalidateMessage = objectMapper.readValue(json, CacheInvalidateMessage.class);

            // 过滤自身发布的消息，避免冗余处理
            if (selfNodeId.equals(invalidateMessage.getSourceNodeId())) {
                log.debug("[缓存失效广播] 忽略自身发布的消息, sourceNodeId={}", selfNodeId);
                return;
            }

            String cacheName = invalidateMessage.getCacheName();
            String key = invalidateMessage.getKey();

            switch (cacheName) {
                case CacheInvalidateMessage.CACHE_FLASH_SALE_DETAIL:
                    if (CacheInvalidateMessage.KEY_ALL.equals(key)) {
                        flashSaleDetailCache.invalidateAll();
                    } else {
                        flashSaleDetailCache.invalidate(key);
                    }
                    break;
                case CacheInvalidateMessage.CACHE_ACTIVE_FLASH_SALE:
                    if (CacheInvalidateMessage.KEY_ALL.equals(key)) {
                        activeFlashSaleCache.invalidateAll();
                    } else {
                        activeFlashSaleCache.invalidate(key);
                    }
                    break;
                case CacheInvalidateMessage.CACHE_ITEM:
                    if (CacheInvalidateMessage.KEY_ALL.equals(key)) {
                        itemCache.invalidateAll();
                    } else {
                        itemCache.invalidate(key);
                    }
                    break;
                default:
                    log.warn("[缓存失效广播] 未知 cacheName={}, 忽略", cacheName);
                    return;
            }
            log.info("[缓存失效广播] 已失效本地 Caffeine, cacheName={}, key={}", cacheName, key);
        } catch (Exception e) {
            log.error("[缓存失效广播] 消息处理失败, error={}", e.getMessage(), e);
        }
    }
}
