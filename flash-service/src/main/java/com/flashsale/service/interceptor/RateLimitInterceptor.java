package com.flashsale.service.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.annotation.RateLimit;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.result.ResultVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 接口限流拦截器 —— Redis ZSET 滑动窗口.
 * <p>
 * 配合 {@link RateLimit} 注解使用.
 * 每个请求以当前毫秒时间戳写入 ZSET，窗口外的记录自动清理.
 * <p>
 * 仅对标注了 {@link RateLimit} 的方法生效，未标注的接口直接放行.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "rate:limit:";

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate,
                                DefaultRedisScript<Long> rateLimitScript,
                                ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        String subject = resolveSubject(request);
        String redisKey = RATE_LIMIT_KEY_PREFIX + rateLimit.key() + ":" + subject;
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.windowSeconds() * 1000L;

        Long count = stringRedisTemplate.execute(
                rateLimitScript,
                List.of(redisKey),
                String.valueOf(windowStart),
                String.valueOf(now),
                String.valueOf(rateLimit.windowSeconds())
        );

        if (count != null && count >= rateLimit.permits()) {
            log.warn("[接口限流] 超限, key={}, subject={}, permits={}, window={}s",
                    rateLimit.key(), subject, rateLimit.permits(), rateLimit.windowSeconds());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    ResultVO.fail(ResultCode.RATE_LIMITED, rateLimit.message())));
            return false;
        }

        return true;
    }

    /**
     * 解析限流主体：已认证用 userId，未认证用客户端 IP.
     */
    private String resolveSubject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return String.valueOf(userId);
        }
        return "ip:" + getClientIp(request);
    }

    /**
     * 获取客户端真实 IP（优先从反向代理头获取）.
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
