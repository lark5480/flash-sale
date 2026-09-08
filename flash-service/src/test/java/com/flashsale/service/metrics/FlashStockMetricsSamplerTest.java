package com.flashsale.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.service.stock.FlashStockState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link FlashStockMetricsSampler} 单元测试
 * <p>
 * 核心回归点：库存变化后 Gauge 必须跟着刷新。MultiGauge 绑定的是 Row 里传入的对象引用，
 * 若传不可变数值，指标会永久冻结在第一次采样的值上——这类问题在功能测试里不可见，
 * 只有盯着 Prometheus 曲线才会发现，因此用单测锁死。
 */
class FlashStockMetricsSamplerTest {

    private static final Long SALE_ID = 1L;
    private static final String STOCK_KEY = FlashStockState.stockKey(SALE_ID);
    private static final String INFLIGHT_KEY = FlashStockState.inflightKey(SALE_ID);
    private static final String GAUGE_REMAINING = "flashsale.stock.remaining";
    private static final String GAUGE_INFLIGHT = "flashsale.stock.inflight";
    private static final String GAUGE_DRIFT = "flashsale.stock.drift";

    private final Map<String, String> redisData = new HashMap<>();

    private SimpleMeterRegistry registry;
    private FlashStockMetricsSampler sampler;

    /**
     * 单测环境没有 Spring 上下文，MyBatis-Plus 的 TableInfo 未初始化，
     * LambdaQueryWrapper 解析 FlashSale::getId 这类方法引用会抛
     * "can not find lambda cache for this entity"。这里手动初始化，模拟真实启动后的状态。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, FlashSale.class);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        FlashSaleMapper flashSaleMapper = mock(FlashSaleMapper.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> redisData.get(invocation.getArgument(0)));

        FlashSale sale = new FlashSale();
        sale.setId(SALE_ID);
        sale.setStock(100);
        when(flashSaleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sale));

        registry = new SimpleMeterRegistry();
        sampler = new FlashStockMetricsSampler(flashSaleMapper, stringRedisTemplate,
                new FlashSaleMetrics(registry));
    }

    @Test
    @DisplayName("库存变化后 Gauge 必须刷新，不能冻结在首次采样值")
    void gaugeRefreshesWhenStockChanges() {
        redisData.put(STOCK_KEY, "100");
        redisData.put(INFLIGHT_KEY, "0");

        sampler.sampleActiveFlashSales();

        assertThat(gaugeValue(GAUGE_REMAINING)).isEqualTo(100.0);

        // 用户抢走 3 件：Redis 库存 −3，其中 3 笔在途尚未落库
        redisData.put(STOCK_KEY, "97");
        redisData.put(INFLIGHT_KEY, "3");

        sampler.sampleActiveFlashSales();

        assertThat(gaugeValue(GAUGE_REMAINING)).isEqualTo(97.0);
        assertThat(gaugeValue(GAUGE_INFLIGHT)).isEqualTo(3.0);
        // DB 库存 100 = Redis 97 + 在途 3，漂移为 0
        assertThat(gaugeValue(GAUGE_DRIFT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("库存键缺失（未预热或已过期）时不产出该场次的指标")
    void missingStockKeyIsSkipped() {
        redisData.put(INFLIGHT_KEY, "0");

        sampler.sampleActiveFlashSales();

        assertThat(registry.find(GAUGE_REMAINING).gauge()).isNull();
    }

    @Test
    @DisplayName("Redis 读取异常时保留上一次的值并记采样错误")
    void redisFailureKeepsLastValue() {
        redisData.put(STOCK_KEY, "100");
        redisData.put(INFLIGHT_KEY, "0");
        sampler.sampleActiveFlashSales();
        assertThat(gaugeValue(GAUGE_REMAINING)).isEqualTo(100.0);

        redisData.put(STOCK_KEY, "not-a-number");

        sampler.sampleActiveFlashSales();

        assertThat(gaugeValue(GAUGE_REMAINING)).isEqualTo(100.0);
        assertThat(registry.get("flashsale.metrics.sample.error").counter().count()).isEqualTo(1.0);
    }

    private double gaugeValue(String name) {
        return registry.get(name).tag("flashSaleId", String.valueOf(SALE_ID)).gauge().value();
    }
}
