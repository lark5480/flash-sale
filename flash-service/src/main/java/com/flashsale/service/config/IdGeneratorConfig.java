package com.flashsale.service.config;

import com.flashsale.common.util.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ID 生成器配置 — 将 SnowflakeIdGenerator 注册为 Spring Bean
 */
@Configuration
public class IdGeneratorConfig {

    /** 单节点开发环境，workerId=0, datacenterId=0 */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(0, 0);
    }
}