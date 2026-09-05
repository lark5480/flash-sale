package com.flashsale.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.service.metrics.FlashSaleMetrics;

/**
 * {@link FlashStockState} 单元测试 —— 锁住「flash:stock 是业务状态而非缓存」这条 P0 不变量。
 * <p>
 * 用 Mockito 而非真实 Redis：本机没有 Redis 可用，而这里要验证的恰恰是本类的读写口径
 * （键存在就不动它、重建减去在途、TTL 覆盖整场），与 Redis 服务端行为无关。
 * Lua 脚本自身的语义需要集成测试（Testcontainers）覆盖，尚未补。
 */
class FlashStockStateTest {

    private static final Long SALE_ID = 1L;
    private static final Long USER_ID = 9L;
    private static final String STOCK_KEY = "flash:stock:1";
    private static final String PURCHASED_KEY = "flash:user:purchased:1:9";
    private static final String INFLIGHT_KEY = "flash:inflight:1";

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DefaultRedisScript<Long> restoreScript;
    private FlashSaleMetrics flashSaleMetrics;
    private FlashStockState flashStockState;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        flashSaleMetrics = mock(FlashSaleMetrics.class);
        restoreScript = new DefaultRedisScript<>("return 1", Long.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        flashStockState = new FlashStockState(stringRedisTemplate, restoreScript, flashSaleMetrics);
    }

    private FlashSale flashSale(int stock, LocalDateTime endTime) {
        FlashSale flashSale = new FlashSale();
        flashSale.setId(SALE_ID);
        flashSale.setStock(stock);
        flashSale.setEndTime(endTime);
        return flashSale;
    }

    private void stubSetnx(boolean written) {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(written);
    }

    // ==================== ensureStockKey ====================

    @Test
    @DisplayName("库存键已存在时绝不覆盖，也不读在途")
    void ensureStockKey_existingKeyIsNeverOverwritten() {
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(true);

        boolean rebuilt = flashStockState.ensureStockKey(flashSale(100, LocalDateTime.now().plusHours(1)));

        assertThat(rebuilt).isFalse();
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOperations, never()).set(anyString(), anyString());
        verify(valueOperations, never()).get(anyString());
        verifyNoInteractions(flashSaleMetrics);
    }

    @Test
    @DisplayName("键缺失时按 DB stock - 在途 重建，TTL 覆盖剩余场次加宽限期")
    void ensureStockKey_rebuildSubtractsInflight() {
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
        when(valueOperations.get(INFLIGHT_KEY)).thenReturn("7");
        stubSetnx(true);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        LocalDateTime endTime = LocalDateTime.now().plusHours(1);
        long remainUpperBound = Duration.between(LocalDateTime.now(), endTime).getSeconds();

        boolean rebuilt = flashStockState.ensureStockKey(flashSale(100, endTime));

        assertThat(rebuilt).isTrue();
        verify(valueOperations).setIfAbsent(eq(STOCK_KEY), value.capture(), ttl.capture(), eq(TimeUnit.SECONDS));
        assertThat(value.getValue()).isEqualTo("93");
        // TTL = 剩余时间 + 宽限期：至少覆盖整个场次，且不会退化成无上限的脏键
        assertThat(ttl.getValue())
                .isBetween(remainUpperBound - 1 + RedisConstants.FLASH_STOCK_GRACE_SECONDS,
                        remainUpperBound + RedisConstants.FLASH_STOCK_GRACE_SECONDS);
        verify(flashSaleMetrics).recordStockRebuild();
    }

    @Test
    @DisplayName("在途超过 DB stock 时重建为零，不放出负库存")
    void ensureStockKey_clampsToZeroWhenInflightExceedsStock() {
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
        when(valueOperations.get(INFLIGHT_KEY)).thenReturn("10");
        stubSetnx(true);

        flashStockState.ensureStockKey(flashSale(3, LocalDateTime.now().plusHours(1)));

        verify(valueOperations).setIfAbsent(eq(STOCK_KEY), eq("0"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("在途键不存在按 0 处理，重建结果等于 DB stock")
    void ensureStockKey_missingInflightKeyCountsAsZero() {
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
        when(valueOperations.get(INFLIGHT_KEY)).thenReturn(null);
        stubSetnx(true);

        flashStockState.ensureStockKey(flashSale(100, LocalDateTime.now().plusHours(1)));

        verify(valueOperations).setIfAbsent(eq(STOCK_KEY), eq("100"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("SETNX 输给并发节点时不算重建，也不计指标")
    void ensureStockKey_losingSetnxRaceReportsNoRebuild() {
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
        when(valueOperations.get(INFLIGHT_KEY)).thenReturn("5");
        stubSetnx(false);

        boolean rebuilt = flashStockState.ensureStockKey(flashSale(100, LocalDateTime.now().plusHours(1)));

        assertThat(rebuilt).isFalse();
        verify(flashSaleMetrics, never()).recordStockRebuild();
    }

    // ==================== deleteStockKey（重新激活前清理） ====================

    @Test
    @DisplayName("重新激活前删除库存键，且只删除库存键")
    void deleteStockKey_removesStockKeyOnly() {
        flashStockState.deleteStockKey(SALE_ID);

        verify(stringRedisTemplate).delete(STOCK_KEY);
    }

    @Test
    @DisplayName("删除库存键失败不影响管理端状态流转（吞异常）")
    void deleteStockKey_swallowsRedisFailures() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).delete(STOCK_KEY);

        assertThatCode(() -> flashStockState.deleteStockKey(SALE_ID)).doesNotThrowAnyException();
    }

    // ==================== readInflight ====================

    @Test
    @DisplayName("在途读取失败按零库存保守处理，重建不放出虚假库存")
    void readInflight_readFailureFailsClosed() {
        when(valueOperations.get(INFLIGHT_KEY)).thenThrow(new RuntimeException("redis down"));
        when(stringRedisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
        stubSetnx(true);

        assertThat(flashStockState.readInflight(SALE_ID)).isEqualTo(Long.MAX_VALUE);

        flashStockState.ensureStockKey(flashSale(100, LocalDateTime.now().plusHours(1)));

        verify(valueOperations).setIfAbsent(eq(STOCK_KEY), eq("0"), anyLong(), eq(TimeUnit.SECONDS));
    }

    // ==================== 归还 / 收敛脚本 ====================

    @Test
    @DisplayName("三种归还模式各自使用正确的脚本模式与键顺序")
    void apply_passesModeAndKeysPerOperation() {
        List<String> keys = List.of(STOCK_KEY, PURCHASED_KEY, INFLIGHT_KEY);

        flashStockState.rollbackReservation(SALE_ID, USER_ID);
        verify(stringRedisTemplate).execute(restoreScript, keys, FlashStockState.MODE_ROLLBACK);

        flashStockState.returnOrderStock(SALE_ID, USER_ID);
        verify(stringRedisTemplate).execute(restoreScript, keys, FlashStockState.MODE_RETURN);

        flashStockState.releaseInflight(SALE_ID, USER_ID);
        verify(stringRedisTemplate).execute(restoreScript, keys, FlashStockState.MODE_RELEASE_INFLIGHT);
    }

    @Test
    @DisplayName("Redis 故障不外泄：取消/归还不能因脚本失败而回滚外层事务")
    void apply_swallowsRedisFailures() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate)
                .execute(any(RedisScript.class), anyList(), any());

        assertThatCode(() -> flashStockState.rollbackReservation(SALE_ID, USER_ID)).doesNotThrowAnyException();
        assertThatCode(() -> flashStockState.returnOrderStock(SALE_ID, USER_ID)).doesNotThrowAnyException();
        assertThatCode(() -> flashStockState.releaseInflight(SALE_ID, USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("键名口径固定，改前缀必须同步脚本与调用点")
    void keyFormatsAreStable() {
        assertThat(FlashStockState.stockKey(SALE_ID)).isEqualTo(STOCK_KEY);
        assertThat(FlashStockState.userPurchasedKey(SALE_ID, USER_ID)).isEqualTo(PURCHASED_KEY);
        assertThat(FlashStockState.inflightKey(SALE_ID)).isEqualTo(INFLIGHT_KEY);
    }
}
