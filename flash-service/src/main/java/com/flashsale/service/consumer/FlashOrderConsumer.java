package com.flashsale.service.consumer;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.mapper.FlashOrderMapper;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.enums.OrderStatusEnum;
import com.flashsale.service.FlashOrderService;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.service.metrics.ConsumeResult;
import com.flashsale.service.metrics.FlashSaleMetrics;
import com.flashsale.service.metrics.OrderFailReason;
import io.micrometer.core.instrument.Timer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消息消费者
 * <p>
 * 通过 RocketMQ 消费下单消息，支持多节点分布式削峰。
 * 幂等保障：结果标记（仅在业务终态后写入）→ DB messageKey 查询 → UNIQUE 索引兜底。
 * 系统异常不写终态标记，交由 RocketMQ 重试，重试时仍会走完整的扣库存逻辑。
 * 事务保护：deductStock + INSERT 在同一事务内，失败自动回滚。
 * <p>
 * 在途计数收敛统一交给 {@link FlashOrderSettler}：只有真正写入终态标记的投递才递减在途，
 * DB 幂等命中只补写标记、不递减，避免重复递减放出虚假库存。
 * <p>
 * 指标归因：业务终态失败（{@link ConsumeResult#BUSINESS_TERMINAL}）是重试也无意义的正常终态，
 * 系统异常（{@link ConsumeResult#SYSTEM_RETRY}）才是故障，两者分开计数，否则故障会被正常终态淹没。
 * <p>
 * 仅在 flash-api 中启用（flash.flash.consumer.enabled=true），
 * flash-admin 不创建此消费者以避免同组冲突。
 */
@Component
@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConstants.FLASH_ORDER_TOPIC,
        selectorExpression = RocketMQConstants.TAG_CREATE,
        consumerGroup = RocketMQConstants.ORDER_CONSUMER_GROUP,
        maxReconsumeTimes = 3
)
public class FlashOrderConsumer implements RocketMQListener<FlashOrderMessage> {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderConsumer.class);

    private final FlashOrderService flashOrderService;
    private final FlashOrderMapper flashOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final FlashSaleMetrics flashSaleMetrics;
    private final FlashOrderSettler flashOrderSettler;

    public FlashOrderConsumer(FlashOrderService flashOrderService,
                              FlashOrderMapper flashOrderMapper,
                              StringRedisTemplate stringRedisTemplate,
                              RedissonClient redissonClient,
                              FlashSaleMetrics flashSaleMetrics,
                              FlashOrderSettler flashOrderSettler) {
        this.flashOrderService = flashOrderService;
        this.flashOrderMapper = flashOrderMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.flashSaleMetrics = flashSaleMetrics;
        this.flashOrderSettler = flashOrderSettler;
    }

    /**
     * 消费入口：统一计时，保证任何出口（含异常重试）都记录消费耗时
     */
    @Override
    public void onMessage(FlashOrderMessage message) {
        Timer.Sample sample = flashSaleMetrics.startTimer();
        try {
            consume(message);
        } finally {
            flashSaleMetrics.stopConsumeTimer(sample);
        }
    }

    /**
     * 消费秒杀下单消息
     * <p>
     * 1. 结果标记判定（DONE / FAILED 为业务终态，跳过重复投递；标记只在终态后写入，不会短路重试）
     * 2. DB 幂等校验（messageKey 查询，结果标记过期后的兜底）
     * 3. Redisson 分布式锁（防止并发消费同一场秒杀）
     * 4. 事务性扣库存 + 创建订单（@Transactional，失败自动回滚）
     */
    private void consume(FlashOrderMessage message) {
        String msgKey = message.getMessageKey();
        log.info("[异步下单] 收到 RocketMQ 消息, messageKey={}, flashSaleId={}, userId={}",
                msgKey, message.getFlashSaleId(), message.getUserId());

        // ========== 1. 终态判定：标记只由本消费者在业务终态后写入 ==========
        String resultKey = RocketMQConstants.MSG_RESULT_KEY + msgKey;
        String settled = stringRedisTemplate.opsForValue().get(resultKey);
        if (settled != null) {
            log.warn("[异步下单] 消息已处于终态 {}, 跳过重复消费, messageKey={}", settled, msgKey);
            flashSaleMetrics.recordConsume(ConsumeResult.DUPLICATE);
            return;
        }

        // ========== 2. DB 幂等校验（结果标记过期时的兜底） ==========
        FlashOrder existingOrder = flashOrderMapper.selectByMessageKey(msgKey);
        if (existingOrder != null) {
            log.warn("[异步下单] DB 幂等命中，订单已存在, messageKey={}, orderId={}",
                    msgKey, existingOrder.getId());
            // 订单由更早的一次投递落库，那笔预扣的在途计数已在那次收敛，这里只补写标记、不再递减
            flashOrderSettler.markSettledOnly(message, RocketMQConstants.RESULT_DONE);
            flashSaleMetrics.recordConsume(ConsumeResult.DUPLICATE);
            return;
        }

        // ========== 3. Redisson 分布式锁 ==========
        String lockKey = RedisConstants.FLASH_LOCK_KEY + message.getFlashSaleId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            lock.lock(10, TimeUnit.SECONDS);

            // ========== 4. 事务性扣库存 + 创建订单 ==========
            FlashOrder order = new FlashOrder();
            order.setUserId(message.getUserId());
            order.setItemId(message.getItemId());
            order.setFlashSaleId(message.getFlashSaleId());
            order.setFlashPrice(message.getFlashPrice());
            order.setMessageKey(msgKey);
            order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());

            FlashOrder created = flashOrderService.deductStockAndCreateOrder(
                    message.getFlashSaleId(), order);

            // created == null 表示事务内部命中 messageKey 幂等：订单由更早一次投递落库，
            // 那笔在途已在那次收敛，此处只补标记；只有本次真正落库才递减在途。
            if (created != null) {
                flashOrderSettler.settleAndRelease(message, RocketMQConstants.RESULT_DONE);
                flashSaleMetrics.recordOrderSuccess();
                flashSaleMetrics.recordConsume(ConsumeResult.CREATED);
                flashSaleMetrics.recordSettleLatency(message.getProduceTime());
                log.info("[异步下单] 订单创建成功, orderId={}, messageKey={}",
                        created.getId(), msgKey);
            } else {
                flashOrderSettler.markSettledOnly(message, RocketMQConstants.RESULT_DONE);
                flashSaleMetrics.recordConsume(ConsumeResult.DUPLICATE);
            }

        } catch (BusinessException e) {
            // 业务终态（库存不足、活动已结束）：重试也不会成功，写 FAILED 让客户端尽早拿到明确结果
            flashSaleMetrics.recordOrderFail(OrderFailReason.BUSINESS_TERMINAL);
            flashSaleMetrics.recordConsume(ConsumeResult.BUSINESS_TERMINAL);
            flashOrderSettler.settleAndRelease(message, RocketMQConstants.RESULT_FAILED);
            log.warn("[异步下单] 业务异常终态不重试, messageKey={}, flashSaleId={}: {}",
                    msgKey, message.getFlashSaleId(), e.getMessage());
        } catch (Exception e) {
            // 系统异常：不写终态标记，交给 RocketMQ 重试重新执行扣库存
            flashSaleMetrics.recordOrderFail(OrderFailReason.SYSTEM_ERROR);
            flashSaleMetrics.recordConsume(ConsumeResult.SYSTEM_RETRY);
            log.error("[异步下单] 系统异常触发重试, messageKey={}, flashSaleId={}",
                    msgKey, message.getFlashSaleId(), e);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
