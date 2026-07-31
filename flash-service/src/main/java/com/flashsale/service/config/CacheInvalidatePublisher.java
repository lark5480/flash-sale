package com.flashsale.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.constant.RedisConstants;
import com.flashsale.service.message.CacheInvalidateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存失效消息发布器
 *
 * <p>在本地节点执行 Caffeine 缓存失效后，通过 Redis Pub/Sub 广播失效消息，
 * 通知其他节点同步失效各自的 Caffeine 缓存。</p>
 *
 * <p>注意：本组件只负责发布消息，不会触发本地 Caffeine 失效（由调用方自行处理），
 * 因此不会与 {@link CacheInvalidateListener} 形成循环。</p>
 *
 * @author flash-sale
 */
@Component
public class CacheInvalidatePublisher {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidatePublisher.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public CacheInvalidatePublisher(StringRedisTemplate stringRedisTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${spring.application.name:unknown}-${server.port:0}") String nodeId) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    /**
     * 发布缓存失效消息到 Redis Pub/Sub 频道
     *
     * @param cacheName 缓存名称，参见 {@link CacheInvalidateMessage} 中的常量
     * @param key       要失效的缓存 key；传 {@link CacheInvalidateMessage#KEY_ALL} 表示 invalidateAll
     */
    public void publish(String cacheName, String key) {
        try {
            CacheInvalidateMessage message = new CacheInvalidateMessage(cacheName, key, nodeId);
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.convertAndSend(RedisConstants.CACHE_INVALIDATE_CHANNEL, json);
            log.info("[缓存失效广播] 已发布, cacheName={}, key={}, sourceNodeId={}", cacheName, key, nodeId);
        } catch (Exception e) {
            // Pub/Sub 失败不影响主流程，仅记录警告
            log.warn("[缓存失效广播] 发布失败, cacheName={}, key={}, error={}", cacheName, key, e.getMessage());
        }
    }
}
