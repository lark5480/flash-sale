package com.flashsale.api.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.result.ResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sentinel 统一限流异常处理
 * <p>
 * 通过 @ControllerAdvice 捕获 Sentinel 抛出的 BlockException，
 * 返回统一 JSON 格式（ResultVO）而非 Sentinel 默认错误页。
 * 使用 @Order(Ordered.HIGHEST_PRECEDENCE) 确保优先于全局异常处理器。
 */
@RestControllerAdvice
@Order(Integer.MIN_VALUE)
public class SentinelExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SentinelExceptionHandler.class);

    /**
     * 处理 Sentinel 流控/熔断/系统保护/热点参数限流异常
     */
    @ExceptionHandler(BlockException.class)
    public ResultVO<Void> handleBlockException(BlockException e) {
        log.warn("[Sentinel] 请求被限流, rule={}, limitApp={}",
                e.getRule(), e.getRule() != null ? e.getRule().getLimitApp() : "unknown");
        return ResultVO.fail(ResultCode.RATE_LIMITED, "系统繁忙，请稍后重试");
    }
}
