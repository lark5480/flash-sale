package com.flashsale.common.constant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 秒杀状态键 TTL 口径测试。
 * <p>
 * 库存 / 限购 / 在途三个键是业务状态而非缓存，它们的 TTL 一旦短于场次剩余时间，
 * 键就会在进行中自然过期，之后的重建只能拿滞后的 DB 值兜底并放出虚假库存。
 */
class RedisConstantsTest {

    @Test
    void stockTtlSeconds_coversRemainingSessionPlusGrace() {
        LocalDateTime endTime = LocalDateTime.now().plusHours(2);

        long ttl = RedisConstants.stockTtlSeconds(endTime);

        assertThat(ttl).isBetween(2 * 3600L - 60 + RedisConstants.FLASH_STOCK_GRACE_SECONDS,
                2 * 3600L + RedisConstants.FLASH_STOCK_GRACE_SECONDS);
    }

    @Test
    void stockTtlSeconds_isNeverShorterThanTheGracePeriod() {
        // 归还侧只改已存在的键：键若早于迟到的取消/退款过期，归还会静默丢失
        assertThat(RedisConstants.stockTtlSeconds(LocalDateTime.now().minusDays(3)))
                .isEqualTo(RedisConstants.FLASH_STOCK_GRACE_SECONDS);
        assertThat(RedisConstants.stockTtlSeconds(null))
                .isEqualTo(RedisConstants.FLASH_STOCK_GRACE_SECONDS);
    }

    @Test
    void stateKeyTtlOutlivesPureCacheTtl_soMidSessionExpiryCannotHappen() {
        long shortestPossibleStateKeyTtl = RedisConstants.stockTtlSeconds(LocalDateTime.now().minusYears(1));

        assertThat(shortestPossibleStateKeyTtl).isGreaterThan(RedisConstants.FLASH_CACHE_TTL);
    }

    @Test
    @DisplayName("randomTtl 的偏移是固定 ±300s，套在小基数上会把约一半写入钳到 1s")
    void randomTtl_absoluteOffsetCollapsesSmallBaseTtl() {
        boolean sawClampedToLowerBound = false;
        for (int i = 0; i < 5000; i++) {
            long ttl = RedisConstants.randomTtl(30L);
            assertThat(ttl).isBetween(1L, 330L);
            if (ttl == 1L) {
                sawClampedToLowerBound = true;
            }
        }
        // 钉住现状而非认可它：active:list 用 randomTtl(30) 因此这层 L2 大部分时间不生效。
        // 若把偏移改成按基数比例取值，本用例应随之更新并同步撤销文档里的已知缺陷说明。
        assertThat(sawClampedToLowerBound).as("base=30 时负偏移被钳成下限 1s").isTrue();
    }

    @Test
    void randomTtl_staysWithinPlusMinus300OfLargeBase() {
        for (int i = 0; i < 5000; i++) {
            assertThat(RedisConstants.randomTtl(RedisConstants.FLASH_CACHE_TTL))
                    .isBetween(3600L - 300L, 3600L + 300L);
        }
    }
}
