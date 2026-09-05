package com.flashsale.service.stock;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.service.metrics.FlashSaleMetrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 秒杀 Redis 状态键（库存 / 限购计数 / 在途计数）的唯一读写入口
 * <p>
 * {@code flash:stock:{id}} 不是缓存而是业务状态：DB 的 stock 永远滞后「Redis 已预扣、
 * MQ 尚未落库」的那部分，任何按 DB stock 直接赋值的路径都会把在途量当成可用库存放出。
 * 因此重建口径（{@code DB stock - 在途}）、归还守卫（只改已存在的键）和 TTL 口径
 * 全部收在这个类里，调用点不再各自实现，避免下一个写键的地方重犯同一个错。
 */
@Component
public class FlashStockState {

    private static final Logger log = LoggerFactory.getLogger(FlashStockState.class);

    /** 归还模式：回滚一次预扣（库存 +1、限购 -1、在途 -1），用于 MQ 发送失败 */
    public static final String MODE_ROLLBACK = "1";
    /** 归还模式：归还一笔已落库订单（库存 +1、限购 -1），用于超时取消 / 退款 */
    public static final String MODE_RETURN = "0";
    /** 归还模式：只收敛在途计数，用于消费者到达业务终态 */
    public static final String MODE_RELEASE_INFLIGHT = "2";

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> stockRestoreScript;
    private final FlashSaleMetrics flashSaleMetrics;

    public FlashStockState(StringRedisTemplate stringRedisTemplate,
                           DefaultRedisScript<Long> stockRestoreScript,
                           FlashSaleMetrics flashSaleMetrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockRestoreScript = stockRestoreScript;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    public static String stockKey(Long flashSaleId) {
        return RedisConstants.FLASH_STOCK_KEY + flashSaleId;
    }

    public static String userPurchasedKey(Long flashSaleId, Long userId) {
        return RedisConstants.FLASH_USER_PURCHASED_KEY + flashSaleId + ":" + userId;
    }

    public static String inflightKey(Long flashSaleId) {
        return RedisConstants.FLASH_INFLIGHT_KEY + flashSaleId;
    }

    /**
     * 库存键缺失时按「DB stock - 在途」补建，键已存在则完全不动它。
     * <p>
     * 用 SETNX 而非 SET：并发下 SET 会覆盖其他节点已被 Lua 扣减的值。
     *
     * @param flashSale 场次（需含 stock 与 endTime）
     * @return true 表示本次调用做了重建
     */
    public boolean ensureStockKey(FlashSale flashSale) {
        String key = stockKey(flashSale.getId());
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return false;
        }
        long inflight = readInflight(flashSale.getId());
        long available = Math.max(0L, flashSale.getStock() - inflight);
        Boolean setResult = stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(available),
                RedisConstants.stockTtlSeconds(flashSale.getEndTime()), TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(setResult)) {
            flashSaleMetrics.recordStockRebuild();
            log.warn("[库存重建] 按 DB 扣减在途后补建库存键, flashSaleId={}, dbStock={}, inflight={}, rebuilt={}",
                    flashSale.getId(), flashSale.getStock(), inflight, available);
        }
        return Boolean.TRUE.equals(setResult);
    }

    /**
     * 删除库存状态键（仅限管理端「重新激活」路径使用）。
     * <p>
     * 活动被结束 / 取消后，库存键会带着旧 DB stock 计算出的值在宽限期内一直保留；
     * 若管理员在停售期间调大了 DB stock 再重新激活，{@link #ensureStockKey} 看到键已存在
     * 会直接跳过，补货便永远不会生效。非活跃状态没有任何购买流量，删除后由激活路径
     * 按新 DB stock − 在途 重建是安全的。其余路径严禁调用，避免重蹈「删键 = 把权威计数
     * 交给滞后的 DB 重建」的覆辙。
     */
    public void deleteStockKey(Long flashSaleId) {
        try {
            stringRedisTemplate.delete(stockKey(flashSaleId));
            log.info("[库存状态] 重新激活前删除旧库存键, flashSaleId={}", flashSaleId);
        } catch (Exception e) {
            log.error("[库存状态] 删除库存键失败, flashSaleId={}", flashSaleId, e);
        }
    }

    /**
     * 读取在途预扣数。
     * <p>
     * 键不存在按 0；读取异常按「库存全不可用」处理（返回极大值使重建结果为 0）——
     * 少卖可以靠归还和重建挽回，多放出去的虚假库存换不回来。
     */
    public long readInflight(Long flashSaleId) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(inflightKey(flashSaleId));
            return raw == null ? 0L : Math.max(0L, Long.parseLong(raw));
        } catch (Exception e) {
            log.error("[在途计数] 读取失败，按零库存保守处理, flashSaleId={}", flashSaleId, e);
            return Long.MAX_VALUE;
        }
    }

    /**
     * 回滚一次预扣：MQ 发送失败时使用，消息从未进入队列，因此不再是「在途」。
     */
    public void rollbackReservation(Long flashSaleId, Long userId) {
        apply(MODE_ROLLBACK, flashSaleId, userId);
    }

    /**
     * 归还一笔已落库订单：库存与限购计数还回 Redis，在途已由消费者收敛，不再重复递减。
     */
    public void returnOrderStock(Long flashSaleId, Long userId) {
        apply(MODE_RETURN, flashSaleId, userId);
    }

    /**
     * 收敛一个在途计数：消费者或死信处理到达业务终态后调用。
     * <p>
     * 只在真正写入终态标记的那条路径上调用；DB 幂等命中说明原投递已收敛过，重复递减会让
     * 在途数偏低，下一次重建就会放出虚假库存。
     */
    public void releaseInflight(Long flashSaleId, Long userId) {
        apply(MODE_RELEASE_INFLIGHT, flashSaleId, userId);
    }

    private void apply(String mode, Long flashSaleId, Long userId) {
        try {
            Long changed = stringRedisTemplate.execute(stockRestoreScript,
                    List.of(stockKey(flashSaleId), userPurchasedKey(flashSaleId, userId), inflightKey(flashSaleId)),
                    mode);
            log.info("[库存状态] 归还脚本执行完成, mode={}, flashSaleId={}, userId={}, changed={}",
                    mode, flashSaleId, userId, changed);
        } catch (Exception e) {
            log.error("[库存状态] 归还脚本执行失败, mode={}, flashSaleId={}, userId={}",
                    mode, flashSaleId, userId, e);
        }
    }
}
