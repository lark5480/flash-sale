package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.mapper.FlashOrderMapper;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.model.enums.OrderStatusEnum;
import com.flashsale.model.vo.FlashOrderVO;
import com.flashsale.service.FlashOrderService;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.common.util.SnowflakeIdGenerator;
import com.flashsale.service.producer.FlashOrderProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单 Service 实现
 * <p>
 * Phase 3 下单流程（Redis Lua + MQ 异步）：
 * 1. Redis Lua 原子扣库存（快速路径）
 * 2. 发送 RocketMQ 消息到消费者异步落库
 * 3. 立即返回"处理中"
 */
@Service
public class FlashOrderServiceImpl implements FlashOrderService {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderServiceImpl.class);

    private final FlashOrderMapper flashOrderMapper;
    private final FlashSaleMapper flashSaleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> stockDeductScript;
    private final FlashOrderProducer flashOrderProducer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public FlashOrderServiceImpl(FlashOrderMapper flashOrderMapper,
                                 FlashSaleMapper flashSaleMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 DefaultRedisScript<Long> stockDeductScript,
                                 FlashOrderProducer flashOrderProducer,
                                 SnowflakeIdGenerator snowflakeIdGenerator) {
        this.flashOrderMapper = flashOrderMapper;
        this.flashSaleMapper = flashSaleMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockDeductScript = stockDeductScript;
        this.flashOrderProducer = flashOrderProducer;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Override
    public FlashOrder createOrder(FlashOrder order) {
        order.setId(snowflakeIdGenerator.nextId());
        flashOrderMapper.insertWithId(order);
        return order;
    }

    @Override
    public FlashOrder getOrderById(Long id) {
        FlashOrder order = flashOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return order;
    }

    @Override
    public IPage<FlashOrder> listOrdersByUser(Long userId, long page, long size) {
        LambdaQueryWrapper<FlashOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashOrder::getUserId, userId)
                .orderByDesc(FlashOrder::getCreateTime);
        return flashOrderMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public IPage<FlashOrder> listAllOrders(long page, long size) {
        LambdaQueryWrapper<FlashOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(FlashOrder::getCreateTime);
        return flashOrderMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public long countByUserAndFlashSale(Long userId, Long flashSaleId) {
        LambdaQueryWrapper<FlashOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashOrder::getUserId, userId)
                .eq(FlashOrder::getFlashSaleId, flashSaleId)
                .ne(FlashOrder::getStatus, OrderStatusEnum.CANCELLED.getCode());
        return flashOrderMapper.selectCount(wrapper);
    }

    /**
     * 查询订单状态（基于 messageKey 查消息处理结果）
     *
     * @param messageKey MQ 消息唯一键
     * @return 订单状态："PROCESSING" 表示消息尚未被消费，"DONE" 表示已处理
     */
    public String getOrderStatus(String messageKey) {
        String idempotentKey = RocketMQConstants.MSG_PROCESSED_KEY + messageKey;
        String value = stringRedisTemplate.opsForValue().get(idempotentKey);
        if (value != null) {
            return "DONE";
        }
        return "PROCESSING";
    }

    /**
     * 秒杀下单 —— Phase 3 版本（Redis Lua + RocketMQ 异步）
     * <p>
     * 流程：
     * 1. DB 基础校验（活动存在、时间窗）
     * 2. Redis Lua 原子扣库存（RTT < 1ms）
     * 3. 生成幂等 messageKey，发送 MQ 消息
     * 4. 立即返回"处理中"——DB 落库由 FlashOrderConsumer 异步完成
     */
    @Override
    public FlashOrderVO purchase(Long flashSaleId, Long userId) {
        // ========== 1. DB 基础校验 ==========
        FlashSale flashSale = flashSaleMapper.selectById(flashSaleId);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀活动不存在");
        }
        if (!flashSale.getStatus().equals(FlashSaleStatusEnum.ACTIVE.getCode())) {
            throw new BusinessException(ResultCode.FLASH_NOT_STARTED);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(flashSale.getStartTime())) {
            throw new BusinessException(ResultCode.FLASH_NOT_STARTED);
        }
        if (now.isAfter(flashSale.getEndTime())) {
            throw new BusinessException(ResultCode.FLASH_ENDED);
        }

        // 确保 Redis 缓存存在
        ensureRedisStock(flashSale);

        // ========== 2. Redis Lua 原子扣库存 ==========
        String stockKey = RedisConstants.FLASH_STOCK_KEY + flashSaleId;
        String userKey = RedisConstants.FLASH_USER_PURCHASED_KEY + flashSaleId + ":" + userId;
        Long luaResult = stringRedisTemplate.execute(
                stockDeductScript,
                List.of(stockKey, userKey),
                String.valueOf(flashSale.getLimitPerUser()),
                String.valueOf(RedisConstants.FLASH_CACHE_TTL)
        );

        if (luaResult == null || luaResult == -1) {
            log.warn("[秒杀下单] Redis 库存不足, flashSaleId={}, userId={}", flashSaleId, userId);
            throw new BusinessException(ResultCode.FLASH_SOLD_OUT);
        }
        if (luaResult == 0) {
            log.warn("[秒杀下单] 用户超过限购, flashSaleId={}, userId={}", flashSaleId, userId);
            throw new BusinessException(ResultCode.FLASH_REPEAT);
        }

        log.info("[秒杀下单] Redis 预扣成功, flashSaleId={}, userId={}", flashSaleId, userId);

        // ========== 3. 生成幂等键 + 发送 MQ 消息 ==========
        // 格式：flashSaleId_userId_timestamp，保证同一用户同场秒杀的消息唯一
        String messageKey = flashSaleId + "_" + userId + "_" + System.currentTimeMillis();

        FlashOrderMessage message = new FlashOrderMessage(
                messageKey, flashSaleId, userId,
                flashSale.getItemId(), flashSale.getFlashPrice()
        );
        try {
            flashOrderProducer.sendCreateOrderMessage(message);
        } catch (Exception e) {
            log.error("[秒杀下单] MQ 发送失败，回滚 Redis 库存, flashSaleId={}, userId={}", flashSaleId, userId, e);
            // 回滚 Redis 库存
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForValue().decrement(userKey);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }

        // ========== 4. 立即返回"处理中" ==========
        FlashOrderVO vo = new FlashOrderVO();
        vo.setFlashSaleId(flashSaleId);
        vo.setUserId(userId);
        // 返回 messageKey，客户端据此轮询订单状态
        vo.setMessageKey(messageKey);
        return vo;
    }

    /**
     * 确保 Redis 库存缓存存在
     * <p>
     * 仅在 Key 不存在时从 DB 加载。不根据值判断，
     * 因为 stock=0 是合法的售罄状态，重新加载会导致超卖。
     */
    private void ensureRedisStock(FlashSale flashSale) {
        String stockKey = RedisConstants.FLASH_STOCK_KEY + flashSale.getId();
        Boolean hasKey = stringRedisTemplate.hasKey(stockKey);
        if (Boolean.TRUE.equals(hasKey)) {
            return;
        }
        log.info("[缓存补充] Redis 库存 Key 不存在，从 DB 加载, flashSaleId={}, stock={}",
                flashSale.getId(), flashSale.getStock());
        stringRedisTemplate.opsForValue().set(stockKey,
                String.valueOf(flashSale.getStock()),
                RedisConstants.FLASH_CACHE_TTL,
                TimeUnit.SECONDS);
    }

    @Override
    public List<FlashOrder> getExpiredPendingOrders(int timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        LambdaQueryWrapper<FlashOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashOrder::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .lt(FlashOrder::getCreateTime, deadline);
        return flashOrderMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrderAndRestoreStock(FlashOrder order) {
        // 1. 标记订单为已取消
        order.setStatus(OrderStatusEnum.CANCELLED.getCode());
        flashOrderMapper.updateById(order);

        // 2. DB 库存归还
        flashSaleMapper.restoreStock(order.getFlashSaleId(), 1);

        // 3. Redis 库存归还
        String stockKey = RedisConstants.FLASH_STOCK_KEY + order.getFlashSaleId();
        stringRedisTemplate.opsForValue().increment(stockKey);

        // 4. Redis 用户购买计数递减
        String userKey = RedisConstants.FLASH_USER_PURCHASED_KEY
                + order.getFlashSaleId() + ":" + order.getUserId();
        stringRedisTemplate.opsForValue().decrement(userKey);

        log.info("[超时取消] 订单已取消并归还库存, orderId={}, flashSaleId={}, userId={}",
                order.getId(), order.getFlashSaleId(), order.getUserId());
    }

    @Override
    public void payOrder(Long orderId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getStatus().equals(OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待支付订单可支付，请刷新单据");
        }
        order.setStatus(OrderStatusEnum.PAID.getCode());
        flashOrderMapper.updateById(order);
        log.info("[支付] 订单支付成功, orderId={}", orderId);
    }

    @Override
    public void payOrder(Long orderId, Long userId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }
        if (!order.getStatus().equals(OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待支付订单可支付，请刷新单据");
        }
        order.setStatus(OrderStatusEnum.PAID.getCode());
        flashOrderMapper.updateById(order);
        log.info("[支付] 用户支付成功, orderId={}, userId={}", orderId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, Long userId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }
        if (!order.getStatus().equals(OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待支付订单可取消");
        }
        cancelOrderAndRestoreStock(order);
        log.info("[取消] 用户主动取消订单, orderId={}, userId={}", orderId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Long orderId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可退款");
        }
        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        flashOrderMapper.updateById(order);

        // 归还 DB 库存
        flashSaleMapper.restoreStock(order.getFlashSaleId(), 1);

        // 归还 Redis 库存
        String stockKey = RedisConstants.FLASH_STOCK_KEY + order.getFlashSaleId();
        stringRedisTemplate.opsForValue().increment(stockKey);

        // 归还用户购买计数
        String userKey = RedisConstants.FLASH_USER_PURCHASED_KEY
                + order.getFlashSaleId() + ":" + order.getUserId();
        stringRedisTemplate.opsForValue().decrement(userKey);

        log.info("[退款] 订单退款成功, orderId={}, flashSaleId={}, userId={}",
                orderId, order.getFlashSaleId(), order.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(Long orderId, Long userId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }
        if (!order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可退款");
        }
        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        flashOrderMapper.updateById(order);

        // 归还 DB 库存
        flashSaleMapper.restoreStock(order.getFlashSaleId(), 1);

        // 归还 Redis 库存
        String stockKey = RedisConstants.FLASH_STOCK_KEY + order.getFlashSaleId();
        stringRedisTemplate.opsForValue().increment(stockKey);

        // 归还用户购买计数
        String userKey = RedisConstants.FLASH_USER_PURCHASED_KEY
                + order.getFlashSaleId() + ":" + order.getUserId();
        stringRedisTemplate.opsForValue().decrement(userKey);

        log.info("[退款] 用户退款成功, orderId={}, flashSaleId={}, userId={}",
                orderId, order.getFlashSaleId(), userId);
    }

    @Override
    public void deleteOrder(Long orderId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getStatus().equals(OrderStatusEnum.CANCELLED.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已取消订单可删除");
        }
        flashOrderMapper.deleteById(orderId);
        log.info("[删除] 管理端删除已取消订单, orderId={}", orderId);
    }

    @Override
    public void deleteOrder(Long orderId, Long userId) {
        FlashOrder order = flashOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }
        if (!order.getStatus().equals(OrderStatusEnum.CANCELLED.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已取消订单可删除");
        }
        flashOrderMapper.deleteById(orderId);
        log.info("[删除] 用户删除已取消订单, orderId={}, userId={}", orderId, userId);
    }

    /**
     * 事务性扣减库存 + 创建订单（供 MQ Consumer 调用）
     * <p>
     * 在同一事务内执行 deductStock 和 INSERT order，
     * 要么同时成功，要么同时回滚，避免库存丢失。
     * messageKey 的 UNIQUE 索引提供 DB 级幂等兜底。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlashOrder deductStockAndCreateOrder(Long flashSaleId, FlashOrder order) {
        // DB 级幂等：messageKey 已存在则跳过
        FlashOrder existing = flashOrderMapper.selectByMessageKey(order.getMessageKey());
        if (existing != null) {
            log.warn("[异步下单] DB 幂等命中，messageKey={} 已存在, orderId={}",
                    order.getMessageKey(), existing.getId());
            return null;
        }

        // 乐观锁扣库存
        int updated = flashSaleMapper.deductStock(flashSaleId);
        if (updated == 0) {
            throw new BusinessException(ResultCode.FLASH_SOLD_OUT, "DB 库存不足");
        }

        // 生成雪花 ID + 创建订单（同一事务，失败则 deductStock 一并回滚）
        order.setId(snowflakeIdGenerator.nextId());
        flashOrderMapper.insertWithId(order);
        log.info("[异步下单] 事务提交: deductStock + createOrder, orderId={}, messageKey={}",
                order.getId(), order.getMessageKey());
        return order;
    }
}
