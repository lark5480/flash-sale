package com.flashsale.service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.service.config.RedisConfig;
import com.flashsale.service.metrics.FlashSaleMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 库存 Lua 脚本对真实 Redis 的集成测试。
 * <p>
 * 单元测试只能钉住 Java 侧的调用口径，脚本真正的原子性、{@code EXISTS} 守卫和
 * 「首次购买才设 TTL」这些语义必须在 Redis 服务端执行才算验证过。这里跑的是
 * {@code RedisConfig} 里同一份 Bean，因此类路径资源与结果类型也一并被覆盖。
 * <p>
 * 无 Docker 的机器（含部分 CI）会整体跳过而不是失败。DB 落库、MQ 投递仍然不在本测试范围内。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockScriptRedisIntegrationTest {

    private static final int SCRIPT_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(SCRIPT_PORT);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static DefaultRedisScript<Long> deductScript;
    private static FlashStockState stockState;

    private static final Long SALE_ID = 1L;
    private static final long STATE_TTL = 3600L;

    @BeforeAll
    static void wireUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        RedisConfig redisConfig = new RedisConfig();
        deductScript = redisConfig.stockDeductScript();
        stockState = new FlashStockState(template, redisConfig.stockRestoreScript(), mock(FlashSaleMetrics.class));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void flushDatabase() {
        try (RedisConnection connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private String stockKey() {
        return FlashStockState.stockKey(SALE_ID);
    }

    private String purchasedKey(long userId) {
        return FlashStockState.userPurchasedKey(SALE_ID, userId);
    }

    private String inflightKey() {
        return FlashStockState.inflightKey(SALE_ID);
    }

    private void seedStock(long value) {
        template.opsForValue().set(stockKey(), String.valueOf(value), STATE_TTL, TimeUnit.SECONDS);
    }

    private long deduct(long userId, int limitPerUser) {
        Long result = template.execute(deductScript,
                List.of(stockKey(), purchasedKey(userId), inflightKey()),
                String.valueOf(limitPerUser), String.valueOf(STATE_TTL), String.valueOf(STATE_TTL));
        return result == null ? Long.MIN_VALUE : result;
    }

    private long valueOf(String key) {
        String raw = template.opsForValue().get(key);
        return raw == null ? Long.MIN_VALUE : Long.parseLong(raw);
    }

    @Test
    @DisplayName("扣减成功：库存 -1、已购 +1、在途 +1，三个键都带上 TTL")
    void deduct_success_updatesAllThreeStateKeys() {
        seedStock(10);

        assertThat(deduct(9L, 2)).isEqualTo(1L);

        assertThat(valueOf(stockKey())).isEqualTo(9L);
        assertThat(valueOf(purchasedKey(9L))).isEqualTo(1L);
        assertThat(valueOf(inflightKey())).isEqualTo(1L);
        assertThat(template.getExpire(purchasedKey(9L))).isBetween(1L, STATE_TTL);
        assertThat(template.getExpire(inflightKey())).isBetween(1L, STATE_TTL);
    }

    @Test
    @DisplayName("TTL 只在首次购买时设置：追加购买不能让计数键随购买滑动")
    void deduct_setsTtlOnlyOnFirstPurchase() throws InterruptedException {
        seedStock(10);

        assertThat(deduct(9L, 2)).isEqualTo(1L);
        long ttlAfterFirst = template.getExpire(purchasedKey(9L));
        Thread.sleep(1_100L);
        assertThat(deduct(9L, 2)).isEqualTo(1L);

        assertThat(valueOf(purchasedKey(9L))).isEqualTo(2L);
        assertThat(template.getExpire(purchasedKey(9L)))
                .as("第二次购买不得重设 TTL")
                .isLessThan(ttlAfterFirst);
    }

    @Test
    @DisplayName("限购为 0/负数按不限购处理，不能把每笔购买都拒掉")
    void deduct_nonPositiveLimitMeansUnlimited() {
        seedStock(5);

        assertThat(deduct(9L, 0)).isEqualTo(1L);
        assertThat(deduct(10L, -1)).isEqualTo(1L);
        assertThat(valueOf(stockKey())).isEqualTo(3L);
    }

    @Test
    @DisplayName("超过限购返回 0，且库存与在途计数完全不动")
    void deduct_overLimit_hasNoSideEffect() {
        seedStock(5);
        assertThat(deduct(9L, 1)).isEqualTo(1L);
        long stockAfterFirst = valueOf(stockKey());
        long inflightAfterFirst = valueOf(inflightKey());

        assertThat(deduct(9L, 1)).isEqualTo(0L);

        assertThat(valueOf(stockKey())).isEqualTo(stockAfterFirst);
        assertThat(valueOf(inflightKey())).isEqualTo(inflightAfterFirst);
        assertThat(valueOf(purchasedKey(9L))).isEqualTo(1L);
    }

    @Test
    @DisplayName("库存键缺失按售罄返回 -1，且严禁在脚本内凭空建键")
    void deduct_missingStockKey_returnsSoldOutAndCreatesNothing() {
        assertThat(deduct(9L, 2)).isEqualTo(-1L);

        assertThat(template.hasKey(stockKey())).isFalse();
        assertThat(template.hasKey(purchasedKey(9L))).isFalse();
        assertThat(template.hasKey(inflightKey())).isFalse();
    }

    @Test
    @DisplayName("库存为 0 返回 -1，已购与在途都不该被记上一笔")
    void deduct_zeroStock_leavesCountersUntouched() {
        seedStock(0);

        assertThat(deduct(9L, 2)).isEqualTo(-1L);

        assertThat(valueOf(stockKey())).isEqualTo(0L);
        assertThat(template.hasKey(purchasedKey(9L))).isFalse();
        assertThat(template.hasKey(inflightKey())).isFalse();
    }

    @Test
    @DisplayName("并发扣减不超卖：200 个用户对 50 件库存，恰好 50 单成功且库存归零")
    void concurrentDeduct_neverOversells() throws Exception {
        int totalStock = 50;
        int callers = 200;
        seedStock(totalStock);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < callers; i++) {
            long userId = 1000L + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (deduct(userId, 1) == 1L) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(totalStock);
        assertThat(valueOf(stockKey())).isZero();
        assertThat(valueOf(inflightKey())).isEqualTo(totalStock);
        Set<String> purchasedKeys = template.keys("flash:user:purchased:*");
        assertThat(purchasedKeys).as("被拒的 150 个请求不应留下已购计数键").hasSize(totalStock);
    }

    @Test
    @DisplayName("回滚模式：库存、限购、在途各回退一次")
    void rollback_restoresStockLimitAndInflight() {
        seedStock(10);
        deduct(9L, 2);

        stockState.rollbackReservation(SALE_ID, 9L);

        assertThat(valueOf(stockKey())).isEqualTo(10L);
        assertThat(valueOf(purchasedKey(9L))).isZero();
        assertThat(valueOf(inflightKey())).isZero();
    }

    @Test
    @DisplayName("归还模式：库存与限购回退，在途保持不动（已由消费者收敛）")
    void returnOrder_keepsInflightUntouched() {
        seedStock(10);
        deduct(9L, 2);

        stockState.returnOrderStock(SALE_ID, 9L);

        assertThat(valueOf(stockKey())).isEqualTo(10L);
        assertThat(valueOf(purchasedKey(9L))).isZero();
        assertThat(valueOf(inflightKey())).isEqualTo(1L);
    }

    @Test
    @DisplayName("只收敛在途：库存与限购都不动，避免把已落库的量重复加回")
    void releaseInflight_onlyConvergesInflight() {
        seedStock(10);
        deduct(9L, 2);
        deduct(10L, 2);

        stockState.releaseInflight(SALE_ID, 9L);

        assertThat(valueOf(stockKey())).isEqualTo(8L);
        assertThat(valueOf(purchasedKey(9L))).isEqualTo(1L);
        assertThat(valueOf(inflightKey())).isEqualTo(1L);
    }

    @Test
    @DisplayName("状态键已全部过期时三种归还都不建键，避免产生无 TTL 的脏键")
    void restore_onExpiredKeys_createsNoKeys() {
        stockState.rollbackReservation(SALE_ID, 9L);
        stockState.returnOrderStock(SALE_ID, 9L);
        stockState.releaseInflight(SALE_ID, 9L);

        assertThat(template.hasKey(stockKey())).isFalse();
        assertThat(template.hasKey(purchasedKey(9L))).isFalse();
        assertThat(template.hasKey(inflightKey())).isFalse();
    }

    @Test
    @DisplayName("在途已为 0 时不再递减，防止负数在途把下一次重建推成虚假库存")
    void restore_neverDrivesCountersBelowZero() {
        seedStock(10);
        template.opsForValue().set(inflightKey(), "0", STATE_TTL, TimeUnit.SECONDS);
        template.opsForValue().set(purchasedKey(9L), "0", STATE_TTL, TimeUnit.SECONDS);

        stockState.rollbackReservation(SALE_ID, 9L);

        assertThat(valueOf(inflightKey())).isZero();
        assertThat(valueOf(purchasedKey(9L))).isZero();
        assertThat(valueOf(stockKey())).isEqualTo(11L);
    }

    @Test
    @DisplayName("在途计数存在的真实意义：键丢失后按 DB stock 减在途重建，不会放出虚假库存")
    void rebuildAfterKeyLoss_subtractsInflight() {
        FlashSale flashSale = new FlashSale();
        flashSale.setId(SALE_ID);
        flashSale.setStock(100);
        flashSale.setEndTime(LocalDateTime.now().plusHours(1));
        template.opsForValue().set(inflightKey(), "7", STATE_TTL, TimeUnit.SECONDS);

        assertThat(stockState.ensureStockKey(flashSale)).isTrue();

        long remain = Duration.between(LocalDateTime.now(), flashSale.getEndTime()).getSeconds();
        assertThat(valueOf(stockKey())).isEqualTo(93L);
        assertThat(template.getExpire(stockKey()))
                .as("重建出来的状态键必须覆盖整场 + 宽限期")
                .isBetween(remain - 1 + RedisConstants.FLASH_STOCK_GRACE_SECONDS,
                        remain + RedisConstants.FLASH_STOCK_GRACE_SECONDS);
    }
}
