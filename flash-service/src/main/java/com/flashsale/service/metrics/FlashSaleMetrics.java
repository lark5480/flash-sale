package com.flashsale.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 秒杀业务指标
 * <p>
 * 通过 Micrometer 注册自定义业务指标，暴露到 /actuator/prometheus 端点。
 * <ul>
 *   <li>下单：成功次数、失败次数（按 {@link OrderFailReason} 归因）、下单耗时</li>
 *   <li>库存：预扣耗时、库存键重建次数、剩余库存 / 在途量 / 库存漂移 Gauge</li>
 *   <li>MQ：发送成功失败次数与耗时、消费结果分布与耗时、下单到落库的端到端延迟</li>
 * </ul>
 * <strong>Prometheus 查询注意</strong>：失败计数与消费结果都带 tag，聚合查询需自行 sum，
 * 例如 {@code sum(rate(flashsale_order_fail_total[1m]))}、{@code sum by (reason) (...)}。
 * <p>
 * 带 tag 的计数器按枚举值一次性预注册并缓存：既保证每个时间序列都有 description，
 * 也避免同名指标出现「有 tag / 无 tag」两种 label 集合导致 Prometheus 抓取失败。
 */
@Component
public class FlashSaleMetrics {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleMetrics.class);

    /** 失败原因 tag 名 */
    private static final String TAG_REASON = "reason";
    /** 消费结果 tag 名 */
    private static final String TAG_RESULT = "result";
    /** 秒杀活动 tag 名，取值来自 DB 主键，只采集活跃场次以控制基数 */
    private static final String TAG_FLASH_SALE_ID = "flashSaleId";

    /** 分布桶：覆盖毫秒级 Redis 操作到秒级 MQ 发送 */
    private static final Duration[] SLO_BUCKETS = {
            Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5)
    };

    private final Counter orderSuccessCounter;
    private final Counter stockRebuildCounter;
    private final Counter mqSendSuccessCounter;
    private final Counter mqSendFailCounter;
    private final Counter sampleErrorCounter;
    private final Map<OrderFailReason, Counter> orderFailCounters;
    private final Map<ConsumeResult, Counter> consumeCounters;

    private final Timer orderTimer;
    private final Timer stockDeductTimer;
    private final Timer mqSendTimer;
    private final Timer consumeTimer;
    private final Timer settleLatencyTimer;

    private final MultiGauge stockRemainingGauge;
    private final MultiGauge stockInflightGauge;
    private final MultiGauge stockDriftGauge;

    /**
     * 各场次的 Gauge 取值容器（按 flashSaleId 缓存）
     * <p>
     * <strong>必须用可变容器</strong>：{@code MultiGauge.Row.of(tags, value)} 会把传入的 Number
     * 绑定给底层 Gauge，若传装箱后的不可变 Long，重复 register 时值不会更新——
     * 指标会永久停留在第一次采样的数值上。这里持有同一个 AtomicLong 并更新其值，
     * register 才能读到新值。
     */
    private final Map<Long, AtomicLong> remainingHolders = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> inflightHolders = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> driftHolders = new ConcurrentHashMap<>();

    public FlashSaleMetrics(MeterRegistry registry) {
        this.orderSuccessCounter = Counter.builder("flashsale.order.success")
                .description("秒杀下单成功次数（订单真正落库）")
                .register(registry);
        this.stockRebuildCounter = Counter.builder("flashsale.stock.rebuild")
                .description("秒杀库存键重建次数，非零说明 Redis 库存状态曾丢失")
                .register(registry);
        this.mqSendSuccessCounter = Counter.builder("flashsale.mq.send")
                .description("秒杀下单消息发送成功次数")
                .tag(TAG_RESULT, "success")
                .register(registry);
        this.mqSendFailCounter = Counter.builder("flashsale.mq.send")
                .description("秒杀下单消息发送失败次数，失败会回滚 Redis 预扣")
                .tag(TAG_RESULT, "fail")
                .register(registry);
        this.sampleErrorCounter = Counter.builder("flashsale.metrics.sample.error")
                .description("库存指标采样失败次数，非零说明采样时 Redis/DB 访问异常")
                .register(registry);

        this.orderFailCounters = registerTaggedCounters(registry, "flashsale.order.fail",
                "秒杀下单失败次数，按 reason 归因", OrderFailReason.class, TAG_REASON);
        this.consumeCounters = registerTaggedCounters(registry, "flashsale.mq.consume",
                "秒杀下单消息消费次数，按 result 区分消费出口", ConsumeResult.class, TAG_RESULT);

        this.orderTimer = buildTimer(registry, "flashsale.order.duration", "下单处理耗时");
        this.stockDeductTimer = buildTimer(registry, "flashsale.stock.deduct.duration", "Redis Lua 预扣库存耗时");
        this.mqSendTimer = buildTimer(registry, "flashsale.mq.send.duration", "MQ 消息同步发送耗时");
        this.consumeTimer = buildTimer(registry, "flashsale.mq.consume.duration", "MQ 消息消费处理耗时");
        this.settleLatencyTimer = buildTimer(registry, "flashsale.order.settle.latency",
                "下单消息发出到订单落库的端到端延迟");

        this.stockRemainingGauge = MultiGauge.builder("flashsale.stock.remaining")
                .description("Redis 剩余可用库存，按活跃秒杀场次采样")
                .register(registry);
        this.stockInflightGauge = MultiGauge.builder("flashsale.stock.inflight")
                .description("在途预扣数（已扣库存未落库），持续不为零说明消费端堵塞")
                .register(registry);
        this.stockDriftGauge = MultiGauge.builder("flashsale.stock.drift")
                .description("库存漂移：DB 库存 −（Redis 库存 + 在途），正常恒为 0，非零说明库存泄漏")
                .register(registry);
    }

    /**
     * 订单落库成功
     */
    public void recordOrderSuccess() {
        orderSuccessCounter.increment();
    }

    /**
     * 下单失败，按原因归因
     *
     * @param reason 失败原因，决定 reason tag 取值
     */
    public void recordOrderFail(OrderFailReason reason) {
        Counter counter = orderFailCounters.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 库存键重建（Redis 库存状态曾丢失）
     */
    public void recordStockRebuild() {
        stockRebuildCounter.increment();
    }

    /**
     * MQ 消息发送结果
     *
     * @param success true 表示同步发送成功
     */
    public void recordMqSend(boolean success) {
        if (success) {
            mqSendSuccessCounter.increment();
        } else {
            mqSendFailCounter.increment();
        }
    }

    /**
     * 消息消费结果
     *
     * @param result 消费出口，决定 result tag 取值
     */
    public void recordConsume(ConsumeResult result) {
        Counter counter = consumeCounters.get(result);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 记录下单到落库的端到端延迟
     *
     * @param produceTimeMillis 消息生产时刻（毫秒），为空或时钟异常时跳过，避免污染分布
     */
    public void recordSettleLatency(Long produceTimeMillis) {
        if (produceTimeMillis == null || produceTimeMillis <= 0) {
            return;
        }
        long latency = System.currentTimeMillis() - produceTimeMillis;
        if (latency < 0) {
            log.warn("[指标] 端到端延迟为负，疑似时钟回拨，跳过本次记录, produceTime={}", produceTimeMillis);
            return;
        }
        settleLatencyTimer.record(latency, TimeUnit.MILLISECONDS);
    }

    /**
     * 库存指标采样失败（Redis / DB 访问异常）
     * <p>
     * 采样失败时保留上一次的 Gauge 值，绝不写入哨兵值，避免污染告警。
     */
    public void recordSampleError() {
        sampleErrorCounter.increment();
    }

    /**
     * 更新库存类 Gauge
     * <p>
     * 使用 MultiGauge 全量覆盖：本次快照中不存在的场次会被自动移除，
     * 活动结束后不会残留时间序列。
     *
     * @param snapshots 各活跃场次的库存快照，空列表表示清空所有 Gauge 行
     */
    public void updateStockGauges(List<StockSnapshot> snapshots) {
        // 清理已不再活跃的场次，避免场次结束后容器无限增长
        Set<Long> activeIds = snapshots.stream()
                .map(StockSnapshot::flashSaleId)
                .collect(Collectors.toSet());
        evictStaleHolders(activeIds);

        for (StockSnapshot snapshot : snapshots) {
            remainingHolders.computeIfAbsent(snapshot.flashSaleId(), key -> new AtomicLong())
                    .set(snapshot.remaining());
            inflightHolders.computeIfAbsent(snapshot.flashSaleId(), key -> new AtomicLong())
                    .set(snapshot.inflight());
            driftHolders.computeIfAbsent(snapshot.flashSaleId(), key -> new AtomicLong())
                    .set(snapshot.drift());
        }

        stockRemainingGauge.register(toRows(snapshots, remainingHolders));
        stockInflightGauge.register(toRows(snapshots, inflightHolders));
        stockDriftGauge.register(toRows(snapshots, driftHolders));
    }

    private void evictStaleHolders(Set<Long> activeIds) {
        remainingHolders.keySet().removeIf(id -> !activeIds.contains(id));
        inflightHolders.keySet().removeIf(id -> !activeIds.contains(id));
        driftHolders.keySet().removeIf(id -> !activeIds.contains(id));
    }

    /**
     * 开始计时
     *
     * @return 计时样本，交由对应的 stopXxx 方法结束
     */
    public Timer.Sample startTimer() {
        return Timer.start();
    }

    /**
     * 结束下单总耗时计时
     *
     * @param sample {@link #startTimer()} 返回的样本
     */
    public void stopTimer(Timer.Sample sample) {
        stop(sample, orderTimer);
    }

    /**
     * 结束 Redis Lua 预扣库存耗时计时
     *
     * @param sample {@link #startTimer()} 返回的样本
     */
    public void stopStockTimer(Timer.Sample sample) {
        stop(sample, stockDeductTimer);
    }

    /**
     * 结束 MQ 发送耗时计时
     *
     * @param sample {@link #startTimer()} 返回的样本
     */
    public void stopMqSendTimer(Timer.Sample sample) {
        stop(sample, mqSendTimer);
    }

    /**
     * 结束消息消费耗时计时
     *
     * @param sample {@link #startTimer()} 返回的样本
     */
    public void stopConsumeTimer(Timer.Sample sample) {
        stop(sample, consumeTimer);
    }

    private void stop(Timer.Sample sample, Timer timer) {
        if (sample != null) {
            sample.stop(timer);
        }
    }

    /**
     * 组装 MultiGauge 行
     * <p>
     * 行里放的是 {@link AtomicLong} 容器而非具体数值：MultiGauge 绑定的是对象引用，
     * 传不可变数值会导致后续采样无法刷新指标。
     */
    private List<MultiGauge.Row<?>> toRows(List<StockSnapshot> snapshots, Map<Long, AtomicLong> holders) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>(snapshots.size());
        for (StockSnapshot snapshot : snapshots) {
            rows.add(MultiGauge.Row.of(
                    Tags.of(TAG_FLASH_SALE_ID, String.valueOf(snapshot.flashSaleId())),
                    holders.get(snapshot.flashSaleId())));
        }
        return rows;
    }

    /**
     * 按枚举值预注册带 tag 的计数器，保证 label 集合固定且每个序列都有 description
     */
    private static <E extends Enum<E>> Map<E, Counter> registerTaggedCounters(MeterRegistry registry,
                                                                              String name,
                                                                              String description,
                                                                              Class<E> enumType,
                                                                              String tagName) {
        Map<E, Counter> counters = new EnumMap<>(enumType);
        for (E constant : enumType.getEnumConstants()) {
            String tagValue = tagValueOf(constant);
            counters.put(constant, Counter.builder(name)
                    .description(description)
                    .tag(tagName, tagValue)
                    .register(registry));
        }
        return counters;
    }

    private static String tagValueOf(Enum<?> constant) {
        if (constant instanceof OrderFailReason reason) {
            return reason.getCode();
        }
        if (constant instanceof ConsumeResult result) {
            return result.getCode();
        }
        return constant.name().toLowerCase();
    }

    private static Timer buildTimer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                // 暴露 histogram bucket，支持 histogram_quantile 计算 P99
                .publishPercentileHistogram()
                .serviceLevelObjectives(SLO_BUCKETS)
                .register(registry);
    }

    /**
     * 单个秒杀场次的库存快照
     *
     * @param flashSaleId 秒杀活动 ID
     * @param remaining   Redis 剩余可用库存
     * @param inflight    在途预扣数（已预扣未落库）
     * @param drift       库存漂移：DB 库存 −（Redis 库存 + 在途），正常恒为 0
     */
    public record StockSnapshot(Long flashSaleId, long remaining, long inflight, long drift) {
    }
}
