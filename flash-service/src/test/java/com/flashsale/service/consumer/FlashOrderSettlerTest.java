package com.flashsale.service.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.service.stock.FlashStockState;

/**
 * {@link FlashOrderSettler} 单元测试 —— 锁住「在途计数只能由写标记成功的一次投递递减」这条不对称规则。
 * <p>
 * 多减一次就是凭空放出虚假库存（超卖方向），少减只会保守地少卖，因此重点断言 never() 的那几条路径：
 * 重复投递、写标记失败、以及只补写标记的 markSettledOnly。
 */
class FlashOrderSettlerTest {

    private static final String MESSAGE_KEY = "1_9_1700000000000";
    private static final String RESULT_KEY = RocketMQConstants.MSG_RESULT_KEY + MESSAGE_KEY;
    private static final Long SALE_ID = 1L;
    private static final Long USER_ID = 9L;

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private FlashStockState flashStockState;
    private FlashOrderSettler flashOrderSettler;
    private FlashOrderMessage message;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        flashStockState = mock(FlashStockState.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        flashOrderSettler = new FlashOrderSettler(stringRedisTemplate, flashStockState);
        message = new FlashOrderMessage(MESSAGE_KEY, SALE_ID, USER_ID, 3L, new BigDecimal("99.00"));
    }

    private void stubMarkerWrite(Boolean written) {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(written);
    }

    @Test
    @DisplayName("写入终态标记成功：收敛该笔预扣的在途计数")
    void settleAndRelease_releasesInflightWhenMarkerWritten() {
        stubMarkerWrite(true);

        boolean settled = flashOrderSettler.settleAndRelease(message, RocketMQConstants.RESULT_DONE);

        assertThat(settled).isTrue();
        verify(valueOperations).setIfAbsent(eq(RESULT_KEY), eq(RocketMQConstants.RESULT_DONE),
                eq(RocketMQConstants.MSG_RESULT_TTL), eq(TimeUnit.SECONDS));
        verify(flashStockState).releaseInflight(SALE_ID, USER_ID);
    }

    @Test
    @DisplayName("重复投递拿不到标记写入权：不递减在途")
    void settleAndRelease_duplicateDeliveryNeverReleasesInflight() {
        stubMarkerWrite(false);

        boolean settled = flashOrderSettler.settleAndRelease(message, RocketMQConstants.RESULT_FAILED);

        assertThat(settled).isFalse();
        verify(flashStockState, never()).releaseInflight(anyLong(), anyLong());
    }

    @Test
    @DisplayName("写标记抛异常：按未收敛处理，不递减在途")
    void settleAndRelease_markerWriteFailureNeverReleasesInflight() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("redis down"));

        boolean settled = flashOrderSettler.settleAndRelease(message, RocketMQConstants.RESULT_DONE);

        assertThat(settled).isFalse();
        verify(flashStockState, never()).releaseInflight(anyLong(), anyLong());
    }

    @Test
    @DisplayName("补写终态标记只写键：原投递已收敛过在途，绝不重复递减")
    void markSettledOnly_writesMarkerButNeverReleasesInflight() {
        stubMarkerWrite(true);

        boolean written = flashOrderSettler.markSettledOnly(message, RocketMQConstants.RESULT_DONE);

        assertThat(written).isTrue();
        verify(valueOperations).setIfAbsent(eq(RESULT_KEY), eq(RocketMQConstants.RESULT_DONE),
                eq(RocketMQConstants.MSG_RESULT_TTL), eq(TimeUnit.SECONDS));
        verifyNoInteractions(flashStockState);
    }
}
