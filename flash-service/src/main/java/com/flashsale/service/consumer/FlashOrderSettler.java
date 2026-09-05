package com.flashsale.service.consumer;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.service.stock.FlashStockState;

/**
 * 秒杀消息终态收敛器 —— 把「写终态标记」和「递减在途预扣计数」绑成一个原子语义的操作。
 * <p>
 * 在途计数（{@code flash:inflight:{flashSaleId}}）在 Redis 预扣 Lua 里 +1，
 * 必须且只能在消息到达业务终态时 -1 一次。多减会把已成交的量当成可用库存放出（超卖方向），
 * 少减只会保守地少卖（可接受）。因此这里用标记的 SETNX 结果作为「本次是否由我收敛」的凭据：
 * 只有真正写入标记的调用方才递减在途，重复投递与死信补写都拿不到这个权利。
 * <p>
 * 调用方：{@link FlashOrderConsumer}（订单落库成功 / 业务异常终态）、
 * {@link FlashOrderDeadLetterConsumer}（重试耗尽）。
 *
 * @author flash-sale
 */
@Component
public class FlashOrderSettler {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderSettler.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final FlashStockState flashStockState;

    public FlashOrderSettler(StringRedisTemplate stringRedisTemplate,
                             FlashStockState flashStockState) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.flashStockState = flashStockState;
    }

    /**
     * 本次投递自己到达了业务终态：写终态标记，并在写入成功时收敛该笔预扣的在途计数。
     *
     * @param message  到达业务终态的消息
     * @param status   {@link RocketMQConstants#RESULT_DONE} 或 {@link RocketMQConstants#RESULT_FAILED}
     * @return true 表示本次调用写入了标记（并已递减在途）
     */
    public boolean settleAndRelease(FlashOrderMessage message, String status) {
        if (!writeMarkerOnce(message, status)) {
            return false;
        }
        // 只减在途：成交的那部分库存已由 deductStock 记到 DB，Redis 库存键此时不该加回来
        flashStockState.releaseInflight(message.getFlashSaleId(), message.getUserId());
        return true;
    }

    /**
     * 订单已由更早的一次投递落库、但终态标记缺失（当时写失败或已过期）时补写标记，
     * <b>不动在途计数</b>。
     * <p>
     * 那笔预扣在首次投递到达终态时已经递减过，再减一次就是凭空放库存（超卖方向）；
     * 反之若首次投递没来得及减，这里少减只会保守地少卖，键随场次 TTL 一起消失。
     * 两相权衡，宁少减不多减。
     */
    public boolean markSettledOnly(FlashOrderMessage message, String status) {
        return writeMarkerOnce(message, status);
    }

    private boolean writeMarkerOnce(FlashOrderMessage message, String status) {
        String resultKey = RocketMQConstants.MSG_RESULT_KEY + message.getMessageKey();
        boolean written;
        try {
            written = Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
                    resultKey, status, RocketMQConstants.MSG_RESULT_TTL, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.error("[消息终态] 写标记失败, messageKey={}, status={}", message.getMessageKey(), status, e);
            return false;
        }

        if (!written) {
            log.info("[消息终态] 标记已存在，本次不重复收敛在途, messageKey={}, status={}",
                    message.getMessageKey(), status);
        }
        return written;
    }
}
