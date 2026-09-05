package com.flashsale.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 秒杀业务指标
 * <p>
 * 通过 Micrometer 注册自定义业务指标，暴露到 /actuator/prometheus 端点。
 * 指标：下单成功次数、下单失败次数、下单耗时、库存键重建次数。
 */
@Component
public class FlashSaleMetrics {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleMetrics.class);

    private final Counter orderSuccessCounter;
    private final Counter orderFailCounter;
    private final Counter stockRebuildCounter;
    private final Timer orderTimer;

    public FlashSaleMetrics(MeterRegistry registry) {
        this.orderSuccessCounter = Counter.builder("flashsale.order.success")
                .description("秒杀下单成功次数")
                .register(registry);
        this.orderFailCounter = Counter.builder("flashsale.order.fail")
                .description("秒杀下单失败次数")
                .register(registry);
        this.stockRebuildCounter = Counter.builder("flashsale.stock.rebuild")
                .description("秒杀库存键重建次数，非零说明 Redis 库存状态曾丢失")
                .register(registry);
        this.orderTimer = Timer.builder("flashsale.order.duration")
                .description("下单处理耗时")
                // 暴露 histogram bucket，支持 histogram_quantile 计算 P99
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5))
                .register(registry);
    }

    public void recordOrderSuccess() {
        orderSuccessCounter.increment();
    }

    public void recordOrderFail() {
        orderFailCounter.increment();
    }

    public void recordStockRebuild() {
        stockRebuildCounter.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void stopTimer(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(orderTimer);
        }
    }
}
