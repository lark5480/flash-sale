package com.flashsale.service.consumer;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.mapper.FlashOrderMapper;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.enums.OrderStatusEnum;
import com.flashsale.service.message.FlashOrderMessage;
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
 * 包含幂等校验（Redis SETNX）+ Redisson 分布式锁 + DB 乐观锁扣库存。
 * <p>
 * 仅在 flash-api 中启用（flash.flash.consumer.enabled=true），
 * flash-admin 不创建此消费者以避免同组冲突。
 */
@Component
@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConstants.FLASH_ORDER_TOPIC,
        selectorExpression = RocketMQConstants.TAG_CREATE,
        consumerGroup = RocketMQConstants.ORDER_CONSUMER_GROUP
)
public class FlashOrderConsumer implements RocketMQListener<FlashOrderMessage> {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderConsumer.class);

    private final FlashOrderMapper flashOrderMapper;
    private final FlashSaleMapper flashSaleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public FlashOrderConsumer(FlashOrderMapper flashOrderMapper,
                              FlashSaleMapper flashSaleMapper,
                              StringRedisTemplate stringRedisTemplate,
                              RedissonClient redissonClient) {
        this.flashOrderMapper = flashOrderMapper;
        this.flashSaleMapper = flashSaleMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 消费秒杀下单消息
     * <p>
     * 1. 幂等校验（Redis SETNX）
     * 2. Redisson 分布式锁
     * 3. DB 乐观锁扣库存 + 创建订单
     */
    @Override
    public void onMessage(FlashOrderMessage message) {
        String msgKey = message.getMessageKey();
        log.info("[异步下单] 收到 RocketMQ 消息, messageKey={}, flashSaleId={}, userId={}",
                msgKey, message.getFlashSaleId(), message.getUserId());

        // ========== 1. 幂等校验 ==========
        String idempotentKey = RocketMQConstants.MSG_PROCESSED_KEY + msgKey;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", RocketMQConstants.MSG_PROCESSED_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(success)) {
            log.warn("[异步下单] 消息已处理，跳过重复消费, messageKey={}", msgKey);
            return;
        }

        // ========== 2. Redisson 分布式锁 ==========
        String lockKey = RedisConstants.FLASH_LOCK_KEY + message.getFlashSaleId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            lock.lock(10, TimeUnit.SECONDS);

            // ========== 3. DB 乐观锁兜底扣库存 ==========
            int updated = flashSaleMapper.deductStock(message.getFlashSaleId());
            if (updated == 0) {
                log.error("[异步下单] 消费者 DB 兜底失败, messageKey={}, flashSaleId={}",
                        msgKey, message.getFlashSaleId());
                return;
            }

            // ========== 4. 创建订单 ==========
            FlashOrder order = new FlashOrder();
            order.setUserId(message.getUserId());
            order.setItemId(message.getItemId());
            order.setFlashSaleId(message.getFlashSaleId());
            order.setFlashPrice(message.getFlashPrice());
            order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());
            flashOrderMapper.insert(order);

            log.info("[异步下单] 订单创建成功, orderId={}, messageKey={}", order.getId(), msgKey);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
