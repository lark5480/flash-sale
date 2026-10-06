package com.flashsale.service.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 多级缓存配置 — Caffeine 本地缓存 Bean
 *
 * <p>所有同名 cache 实例供 FlashSaleService / ItemService 注入使用，
 * 实现 CacheName → Redis → DB 三级回源。</p>
 *
 * <p>2026-10-06 变更：三个缓存本就开了 {@code recordStats()}，但只统计、不暴露，
 * 因此 Prometheus 里找不到任何 Caffeine 指标，「多级缓存命中率」只能靠口头描述。
 * 现统一注册到 Micrometer，暴露
 * {@code cache_gets_total{cache=...,result="hit|miss"}}、{@code cache_size}、
 * {@code cache_evictions_total}，使命中率成为可测量、可上大盘的指标。</p>
 */
@Configuration
public class CacheConfig {

    private final MeterRegistry meterRegistry;

    public CacheConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 秒杀详情缓存：TTL 60 秒，最大 500 条 */
    @Bean
    public Cache<String, String> flashSaleDetailCache() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(500)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "flashSaleDetailCache");
        return cache;
    }

    /** 秒杀活动列表缓存（首页热门）：TTL 120 秒，列表变更频率高 */
    @Bean
    public Cache<String, String> activeFlashSaleCache() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(120, TimeUnit.SECONDS)
                .maximumSize(10)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "activeFlashSaleCache");
        return cache;
    }

    /** 商品详情缓存：TTL 120 秒，最大 1000 条 */
    @Bean
    public Cache<String, String> itemCache() {
        Cache<String, String> cache = Caffeine.newBuilder()
                .expireAfterWrite(120, TimeUnit.SECONDS)
                .maximumSize(1000)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "itemCache");
        return cache;
    }
}
