package com.flashsale.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashsale.common.config.FlexibleLocalDateTimeDeserializer;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.model.vo.FlashSaleVO;
import com.flashsale.service.config.CacheInvalidatePublisher;
import com.flashsale.service.metrics.FlashSaleMetrics;
import com.flashsale.service.stock.FlashStockState;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 详情页 / 列表页展示库存的口径测试。
 * <p>
 * 回归场景：秒杀 12 的 DB stock 已是 994，但 {@code flash:sale:12} 这个详情快照仍停留在
 * 激活那一刻的 998（下单只扣 flash:stock 与 DB，从不回写快照）。修复前接口直接把 998
 * 返回给前端，用户看到「连下几单库存纹丝不动」。
 */
class FlashSaleDetailStockTest {

    private static final Long SALE_ID = 12L;
    private static final String DETAIL_KEY = "flash:sale:12";
    private static final String STOCK_KEY = "flash:stock:12";

    private ObjectMapper objectMapper;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private FlashSaleServiceImpl flashSaleService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // 与生产 JacksonConfig 对齐：时间写 ISO 字符串而非时间戳数组，Long 不转 String
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule()
                        .addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer()));
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        FlashStockState flashStockState = new FlashStockState(
                stringRedisTemplate, mock(DefaultRedisScript.class), mock(FlashSaleMetrics.class));

        Cache<String, String> detailCache = Caffeine.newBuilder().build();
        Cache<String, String> activeCache = Caffeine.newBuilder().build();

        flashSaleService = new FlashSaleServiceImpl(
                mock(FlashSaleMapper.class),
                mock(ItemMapper.class),
                stringRedisTemplate,
                objectMapper,
                detailCache,
                activeCache,
                mock(CacheInvalidatePublisher.class),
                flashStockState);
    }

    @Test
    @DisplayName("进行中场次：展示库存取 Redis 权威余量，而非详情快照里的旧值")
    void activeSaleUsesRedisStockInsteadOfStaleSnapshot() throws Exception {
        cacheSnapshot(Integer.valueOf(FlashSaleStatusEnum.ACTIVE.getCode()), 998);
        when(valueOperations.get(STOCK_KEY)).thenReturn("994");

        FlashSaleVO vo = flashSaleService.getDetailWithItem(SALE_ID);

        assertThat(vo.getStock()).isEqualTo(994);
    }

    @Test
    @DisplayName("非进行中场次：没有购买流量，DB stock 才是权威，快照值保持不变")
    void inactiveSaleKeepsDbStock() throws Exception {
        cacheSnapshot(FlashSaleStatusEnum.ENDED.getCode(), 998);
        when(valueOperations.get(STOCK_KEY)).thenReturn("994");

        FlashSaleVO vo = flashSaleService.getDetailWithItem(SALE_ID);

        assertThat(vo.getStock()).isEqualTo(998);
    }

    @Test
    @DisplayName("库存键缺失：无权威计数，回落到详情快照的 DB 值")
    void missingStockKeyFallsBackToSnapshot() throws Exception {
        cacheSnapshot(Integer.valueOf(FlashSaleStatusEnum.ACTIVE.getCode()), 998);
        when(valueOperations.get(STOCK_KEY)).thenReturn(null);

        FlashSaleVO vo = flashSaleService.getDetailWithItem(SALE_ID);

        assertThat(vo.getStock()).isEqualTo(998);
    }

    private void cacheSnapshot(Integer status, int stock) throws Exception {
        FlashSaleVO snapshot = new FlashSaleVO();
        snapshot.setId(SALE_ID);
        snapshot.setItemId(6L);
        snapshot.setStock(stock);
        snapshot.setLimitPerUser(10);
        snapshot.setStartTime(LocalDateTime.of(2026, 9, 1, 0, 0));
        snapshot.setEndTime(LocalDateTime.of(2026, 9, 30, 0, 0));
        snapshot.setStatus(status);
        when(valueOperations.get(DETAIL_KEY)).thenReturn(objectMapper.writeValueAsString(snapshot));
    }
}
