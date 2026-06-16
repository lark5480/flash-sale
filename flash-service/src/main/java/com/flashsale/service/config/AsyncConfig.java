package com.flashsale.service.config;

import org.springframework.context.annotation.Configuration;

/**
 * 异步任务配置
 * <p>
 * 秒杀下单已迁移至 RocketMQ 异步消费，不再需要 @EnableAsync。
 * 保留此配置类以备后续其他异步场景使用。
 */
@Configuration
public class AsyncConfig {
}
