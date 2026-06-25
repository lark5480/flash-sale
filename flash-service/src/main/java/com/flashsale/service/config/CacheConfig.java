package com.flashsale.service.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 多级缓存配置 — Caffeine 本地缓存 Bean
 *
 * <p>所有同名 cache 实例供 FlashSaleService / ItemService 注入使用，
 * 实现 CacheName → Redis → DB 三级回源。</p>
 */
@Configuration
public class CacheConfig {

    /** 秒杀详情缓存：TTL 60 秒，最大 500 条 */
    @Bean
    public Cache<String, String> flashSaleDetailCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(500)
                .recordStats()
                .build();
    }

    /** 秒杀活动列表缓存（首页热门）：TTL 15 秒，列表变更频率高 */
    @Bean
    public Cache<String, String> activeFlashSaleCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.SECONDS)
                .maximumSize(10)
                .recordStats()
                .build();
    }

    /** 商品详情缓存：TTL 120 秒，最大 1000 条 */
    @Bean
    public Cache<String, String> itemCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(120, TimeUnit.SECONDS)
                .maximumSize(1000)
                .recordStats()
                .build();
    }
}
