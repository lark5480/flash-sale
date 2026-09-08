package com.flashsale.service.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.service.metrics.FlashSaleMetrics.StockSnapshot;
import com.flashsale.service.stock.FlashStockState;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存指标采样器
 * <p>
 * 周期性采集活跃场次的「Redis 剩余库存 / 在途量 / 库存漂移」，结果交给
 * {@link FlashSaleMetrics#updateStockGauges(List)} 写入 MultiGauge。
 * <p>
 * <strong>为什么不在 Gauge 里直接读 Redis</strong>：Micrometer 的 Gauge 是 scrape 时同步回调，
 * 若回调里访问 Redis，Prometheus 每抓一次就对每个场次发起一次 Redis 往返，且 Redis 抖动会
 * 拖慢甚至超时 /actuator/prometheus——监控本身被依赖拖垮。这里改为「定时采样 + Gauge 读内存」，
 * scrape 路径零 I/O。代价是指标有采样周期级别的延迟，对库存这类趋势型指标完全够用。
 * <p>
 * 漂移口径：DB 库存 = Redis 库存 + 在途（预扣时 Redis −1、在途 +1；落库时 DB −1、在途 −1），
 * 因此 {@code drift = DB 库存 −（Redis 库存 + 在途）} 恒为 0，非 0 即库存泄漏，是需要告警的信号。
 * <p>
 * 采样失败时保留上一次的 Gauge 值并累加 {@code flashsale.metrics.sample.error}，
 * 绝不写入哨兵值（如 Long.MAX_VALUE）污染告警。
 * <p>
 * <strong>生效范围</strong>：仅在开启 {@code @EnableScheduling} 的应用（当前为 flash-admin）执行，
 * flash-api 未开启调度，只会创建 Bean 而不会采样。库存是全局状态，单实例上报即可。
 */
@Component
public class FlashStockMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(FlashStockMetricsSampler.class);

    /** 采样周期：20s，兼顾实时性与 Redis 访问开销 */
    private static final long SAMPLE_INTERVAL_MS = 20_000L;
    /** 启动后延迟，避开应用启动期的缓存预热 */
    private static final long INITIAL_DELAY_MS = 15_000L;

    /** 单次最多采样的场次，防止活动数量失控导致 Prometheus 时间序列爆炸 */
    private static final int MAX_SAMPLED_SALES = 200;

    private final FlashSaleMapper flashSaleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final FlashSaleMetrics flashSaleMetrics;

    public FlashStockMetricsSampler(FlashSaleMapper flashSaleMapper,
                                    StringRedisTemplate stringRedisTemplate,
                                    FlashSaleMetrics flashSaleMetrics) {
        this.flashSaleMapper = flashSaleMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    /**
     * 采集活跃场次的库存状态并刷新 Gauge
     */
    @Scheduled(fixedRate = SAMPLE_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void sampleActiveFlashSales() {
        List<FlashSale> activeSales;
        try {
            activeSales = loadActiveSales();
        } catch (Exception e) {
            // 连场次列表都查不到时保留上一次的 Gauge 值，不让指标曲线断线
            flashSaleMetrics.recordSampleError();
            log.warn("[指标采样] 查询活跃场次失败，保留上一次采样值, error={}", e.getMessage());
            return;
        }

        List<StockSnapshot> snapshots = new ArrayList<>(activeSales.size());
        int failures = 0;
        for (FlashSale sale : activeSales) {
            // 单个场次异常不影响其它场次的采集
            try {
                StockSnapshot snapshot = sampleOne(sale);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (Exception e) {
                failures++;
                flashSaleMetrics.recordSampleError();
                log.warn("[指标采样] 场次采样失败, flashSaleId={}, error={}", sale.getId(), e.getMessage());
            }
        }

        if (snapshots.isEmpty() && failures > 0) {
            // 全部场次都采样失败（Redis 抖动等）：跳过本次刷新，保留上一次的值。
            // 若此时用空快照覆盖，Gauge 会被清空，曲线断线并可能触发 absent 类告警。
            return;
        }

        flashSaleMetrics.updateStockGauges(snapshots);
    }

    /**
     * 采样单个场次
     *
     * @return 库存快照；库存键不存在（未预热或已过期）时返回 null，不产出错指标
     */
    private StockSnapshot sampleOne(FlashSale sale) {
        String rawStock = stringRedisTemplate.opsForValue().get(FlashStockState.stockKey(sale.getId()));
        if (rawStock == null) {
            return null;
        }
        long remaining = Math.max(0L, Long.parseLong(rawStock));
        long inflight = readInflight(sale.getId());
        long dbStock = sale.getStock() == null ? 0L : sale.getStock().longValue();
        return new StockSnapshot(sale.getId(), remaining, inflight, dbStock - remaining - inflight);
    }

    /**
     * 读取在途计数
     * <p>
     * 不复用 {@link FlashStockState#readInflight(Long)}：该方法在异常时返回 Long.MAX_VALUE
     * 作为业务侧的保守口径，直接写进 Gauge 会把 Prometheus 的坐标和告警全部打坏。
     * 这里异常上抛，由调用方记采样错误并保留上一次的值。
     */
    private long readInflight(Long flashSaleId) {
        String raw = stringRedisTemplate.opsForValue().get(FlashStockState.inflightKey(flashSaleId));
        return raw == null ? 0L : Math.max(0L, Long.parseLong(raw));
    }

    private List<FlashSale> loadActiveSales() {
        LambdaQueryWrapper<FlashSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(FlashSale::getId, FlashSale::getStock)
                .eq(FlashSale::getStatus, FlashSaleStatusEnum.ACTIVE.getCode());
        List<FlashSale> sales = flashSaleMapper.selectList(wrapper);
        if (sales.size() > MAX_SAMPLED_SALES) {
            log.warn("[指标采样] 活跃场次超过采样上限，仅采集前 {} 个, actual={}",
                    MAX_SAMPLED_SALES, sales.size());
            return sales.subList(0, MAX_SAMPLED_SALES);
        }
        return sales;
    }
}
