package com.flashsale.admin.scheduler;

import com.flashsale.model.entity.FlashOrder;
import com.flashsale.service.FlashOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时自动取消定时任务
 * <p>
 * 每 5 分钟扫描一次，将超过 15 分钟未支付的订单自动取消，
 * 并归还 DB 库存 + Redis 库存。
 */
@Component
public class OrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderScheduler.class);
    private static final int ORDER_TIMEOUT_MINUTES = 15;

    private final FlashOrderService flashOrderService;

    public OrderScheduler(FlashOrderService flashOrderService) {
        this.flashOrderService = flashOrderService;
    }

    @Scheduled(fixedRate = 300_000)
    public void cancelExpiredOrders() {
        List<FlashOrder> expiredOrders = flashOrderService.getExpiredPendingOrders(ORDER_TIMEOUT_MINUTES);
        if (expiredOrders.isEmpty()) {
            return;
        }
        log.info("[超时取消] 发现 {} 个超时未支付订单", expiredOrders.size());
        for (FlashOrder order : expiredOrders) {
            try {
                flashOrderService.cancelOrderAndRestoreStock(order);
            } catch (Exception e) {
                log.error("[超时取消] 取消订单失败, orderId={}, error={}", order.getId(), e.getMessage());
            }
        }
    }
}
