package com.flashsale.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
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
import com.flashsale.service.stock.FlashStockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

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
    private final FlashStockState flashStockState;
    private final FlashOrderProducer flashOrderProducer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public FlashOrderServiceImpl(FlashOrderMapper flashOrderMapper,
                                 FlashSaleMapper flashSaleMapper,
                                 StringRedisTemplate stringRedisTemplate,
                                 DefaultRedisScript<Long> stockDeductScript,
                                 FlashStockState flashStockState,
                                 FlashOrderProducer flashOrderProducer,
                                 SnowflakeIdGenerator snowflakeIdGenerator) {
        this.flashOrderMapper = flashOrderMapper;
        this.flashSaleMapper = flashSaleMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockDeductScript = stockDeductScript;
        this.flashStockState = flashStockState;
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
    public FlashOrder getOrderById(Long id, Long userId) {
        FlashOrder order = getOrderById(id);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }
        return order;
    }

    @Override
    public IPage<FlashOrderVO> listOrdersByUser(Long userId, long page, long size, Integer status, String keyword) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        return flashOrderMapper.selectUserOrderPage(new Page<>(page, size), userId, status, kw);
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
     * 查询订单处理状态（基于 MQ 消息处理结果标记）
     * <p>
     * 结果标记只由消费者在业务终态后写入，因此 PROCESSING 同时覆盖
     * 「消息尚未被消费」与「系统异常正在重试中」两种情况。
     *
     * @param messageKey MQ 消息唯一键
     * @return {@code DONE} 订单已创建、{@code FAILED} 业务终态失败、{@code PROCESSING} 仍在处理
     */
    @Override
    public String getOrderStatus(String messageKey) {
        String status = stringRedisTemplate.opsForValue()
                .get(RocketMQConstants.MSG_RESULT_KEY + messageKey);
        return status != null ? status : RocketMQConstants.RESULT_PROCESSING;
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
    @SentinelResource(
            value = "flashSale_purchase",
            blockHandler = "purchaseBlock",
            fallback = "purchaseFallback"
    )
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

            // 确保 Redis 库存状态键存在（缺失时按 DB stock - 在途 重建）
            flashStockState.ensureStockKey(flashSale);

            // ========== 2. Redis Lua 原子扣库存 ==========
            long stateTtl = RedisConstants.stockTtlSeconds(flashSale.getEndTime());
            Long luaResult = stringRedisTemplate.execute(
                    stockDeductScript,
                    List.of(FlashStockState.stockKey(flashSaleId),
                            FlashStockState.userPurchasedKey(flashSaleId, userId),
                            FlashStockState.inflightKey(flashSaleId)),
                    String.valueOf(flashSale.getLimitPerUser()),
                    String.valueOf(stateTtl),
                    String.valueOf(stateTtl)
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
                // 回滚 Redis：库存 +1、限购计数 -1、在途计数 -1（消息未进入 MQ，不再是在途）
                flashStockState.rollbackReservation(flashSaleId, userId);
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
     * Sentinel 流控/降级处理
     * <p>
     * 当 purchase() 触发 Sentinel 流控规则或熔断规则时，执行此方法。
     */
    public FlashOrderVO purchaseBlock(Long flashSaleId, Long userId, BlockException ex) {
        log.warn("[Sentinel] 下单被限流降级, flashSaleId={}, userId={}, rule={}",
                flashSaleId, userId, ex.getRule() != null ? ex.getRule().getLimitApp() : "unknown");
        throw new BusinessException(ResultCode.RATE_LIMITED, "系统繁忙，请稍后重试");
    }

    /**
     * Sentinel 业务异常兜底
     * <p>
     * 当 purchase() 抛出未捕获异常时，执行此方法。
     * 业务异常（BusinessException）直接抛出，不包装；系统异常包装为 SYSTEM_ERROR。
     */
    public FlashOrderVO purchaseFallback(Long flashSaleId, Long userId, Throwable t) {
        if (t instanceof BusinessException) {
            log.warn("[Sentinel] 业务异常直接抛出, flashSaleId={}, userId={}, msg={}",
                    flashSaleId, userId, t.getMessage());
            throw (BusinessException) t;
        }
        log.error("[Sentinel] 系统异常触发降级, flashSaleId={}, userId={}", flashSaleId, userId, t);
        throw new BusinessException(ResultCode.SYSTEM_ERROR, "系统异常，请稍后重试");
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

        // 3. Redis 库存/限购/在途统一归还（订单已落库，在途由消费者递减，此处不再动）
        flashStockState.returnOrderStock(order.getFlashSaleId(), order.getUserId());

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

        // 归还 Redis 库存/限购计数（订单已落库，在途已由消费者递减）
        flashStockState.returnOrderStock(order.getFlashSaleId(), order.getUserId());

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

        // 归还 Redis 库存/限购计数（订单已落库，在途已由消费者递减）
        flashStockState.returnOrderStock(order.getFlashSaleId(), order.getUserId());

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

        // ========== 限购 DB 兜底 ==========
        // 限购原本只由 Redis 限购计数键守住，而那个键会过期/丢失：
        // 一旦丢失，「每人限购 N 件」在下侧无声变成「不限购」。
        checkPurchaseLimit(flashSaleId, order.getUserId());

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

    /**
     * 限购 DB 兜底校验（供 {@link #deductStockAndCreateOrder} 在扣库存前调用）。
     * <p>
     * limitPerUser 为 null 或 &le; 0 视为不限购，与 Redis 侧 Lua 的放行口径一致。
     * <p>
     * 这里用普通 COUNT 而不加 FOR UPDATE：FlashOrderConsumer 在整个调用期间持有
     * {@code flash:lock:{flashSaleId}} 的 Redisson 锁，同一场次的消费已被串行化，
     * 且上一笔事务在锁释放前就已提交，不存在「两个并发事务各自 COUNT 到 0」的窗口。
     */
    private void checkPurchaseLimit(Long flashSaleId, Long userId) {
        FlashSale flashSale = flashSaleMapper.selectById(flashSaleId);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀活动不存在或已删除");
        }
        Integer limitPerUser = flashSale.getLimitPerUser();
        if (limitPerUser == null || limitPerUser <= 0) {
            return;
        }
        int occupied = flashOrderMapper.countQuotaOccupied(flashSaleId, userId);
        if (occupied >= limitPerUser) {
            log.warn("[异步下单] 限购兜底拦截, flashSaleId={}, userId={}, occupied={}, limit={}",
                    flashSaleId, userId, occupied, limitPerUser);
            throw new BusinessException(ResultCode.FLASH_REPEAT, "已达每人限购数量");
        }
    }
}
