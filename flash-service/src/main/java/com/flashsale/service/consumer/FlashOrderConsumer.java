package com.flashsale.service.consumer;

import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.mapper.FlashOrderMapper;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.enums.OrderStatusEnum;
import com.flashsale.service.FlashOrderService;
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
 * 三层幂等保障：Redis SETNX → DB messageKey 查询 → UNIQUE 索引兜底。
 * 事务保护：deductStock + INSERT 在同一事务内，失败自动回滚。
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

    private final FlashOrderService flashOrderService;
    private final FlashOrderMapper flashOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public FlashOrderConsumer(FlashOrderService flashOrderService,
                              FlashOrderMapper flashOrderMapper,
                              StringRedisTemplate stringRedisTemplate,
                              RedissonClient redissonClient) {
        this.flashOrderService = flashOrderService;
        this.flashOrderMapper = flashOrderMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 消费秒杀下单消息
     * <p>
     * 1. Redis 幂等校验（SETNX，快速过滤重复消息）
     * 2. DB 幂等校验（messageKey 查询，Redis key 被驱逐后的兜底）
     * 3. Redisson 分布式锁（防止并发消费同一场秒杀）
     * 4. 事务性扣库存 + 创建订单（@Transactional，失败自动回滚）
     */
    @Override
    public void onMessage(FlashOrderMessage message) {
        String msgKey = message.getMessageKey();
        log.info("[异步下单] 收到 RocketMQ 消息, messageKey={}, flashSaleId={}, userId={}",
                msgKey, message.getFlashSaleId(), message.getUserId());

        // ========== 1. Redis 幂等校验（快速路径） ==========
        String idempotentKey = RocketMQConstants.MSG_PROCESSED_KEY + msgKey;
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", RocketMQConstants.MSG_PROCESSED_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(success)) {
            log.warn("[异步下单] Redis 幂等命中，跳过重复消费, messageKey={}", msgKey);
            return;
        }

        // ========== 2. DB 幂等校验（Redis key 被驱逐时的兜底） ==========
        FlashOrder existingOrder = flashOrderMapper.selectByMessageKey(msgKey);
        if (existingOrder != null) {
            log.warn("[异步下单] DB 幂等命中，订单已存在, messageKey={}, orderId={}",
                    msgKey, existingOrder.getId());
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

            if (created != null) {
                log.info("[异步下单] 订单创建成功, orderId={}, messageKey={}",
                        created.getId(), msgKey);
            }

        } catch (BusinessException e) {
            log.warn("[异步下单] 业务异常不重试, messageKey={}, flashSaleId={}: {}",
                    msgKey, message.getFlashSaleId(), e.getMessage());
        } catch (Exception e) {
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
