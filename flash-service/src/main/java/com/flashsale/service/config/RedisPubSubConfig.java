package com.flashsale.service.config;

import com.flashsale.common.constant.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置 — 订阅缓存失效频道
 *
 * <p>创建 {@link RedisMessageListenerContainer} 并将 {@link CacheInvalidateListener}
 * 绑定到 {@code cache:invalidate} 频道，实现跨节点 Caffeine 缓存失效广播。</p>
 *
 * @author flash-sale
 */
@Configuration
public class RedisPubSubConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisPubSubConfig.class);

    /**
     * Redis 消息监听容器
     *
     * <p>订阅缓存失效频道，收到消息后交由 {@link CacheInvalidateListener} 处理。</p>
     *
     * @param connectionFactory Redis 连接工厂
     * @param listener          缓存失效消息监听器
     * @return 监听容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidateListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RedisConstants.CACHE_INVALIDATE_CHANNEL));
        log.info("[RedisPubSub] 已订阅频道: {}", RedisConstants.CACHE_INVALIDATE_CHANNEL);
        return container;
    }
}
