package com.flashsale.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解 —— Redis 滑动窗口实现.
 * <p>
 * 加在 Controller 方法上，超限时返回 {@code ResultVO.fail(ResultCode.RATE_LIMITED, msg)}.
 * <p>
 * 示例: {@code @RateLimit(key = "purchase", permits = 5, windowSeconds = 5)}
 * — 同一用户 5 秒内最多 5 次请求.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 key，最终 Redis key 为 rate:limit:{key}:{userId} */
    String key();

    /** 时间窗口内允许的最大请求次数，默认 10 */
    int permits() default 10;

    /** 时间窗口长度（秒），默认 1 */
    int windowSeconds() default 1;

    /** 超限提示信息 */
    String message() default "request too frequent";
}
