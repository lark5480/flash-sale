package com.flashsale.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.service.interceptor.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 —— 注册限流拦截器.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final ObjectMapper objectMapper;

    public WebMvcConfig(StringRedisTemplate stringRedisTemplate,
                        DefaultRedisScript<Long> rateLimitScript,
                        ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(
                stringRedisTemplate, rateLimitScript, objectMapper))
                .addPathPatterns("/api/**")
                .order(1);
    }
}
