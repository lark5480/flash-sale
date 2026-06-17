package com.flashsale.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.annotation.RateLimit;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.vo.FlashOrderVO;
import com.flashsale.service.FlashOrderService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 秒杀订单 Controller（用户端）
 */
@RestController
@RequestMapping("/api")
public class FlashOrderController {

    private final FlashOrderService flashOrderService;

    public FlashOrderController(FlashOrderService flashOrderService) {
        this.flashOrderService = flashOrderService;
    }

    /**
     * 秒杀下单 —— Phase 3 异步版本
     * <p>
     * 返回 messageKey 供客户端轮询订单状态
     */
    @RateLimit(key = "purchase", permits = 5, windowSeconds = 5)
    @PostMapping("/flash-sale/{flashSaleId}/purchase")
    public ResultVO<Map<String, Object>> purchase(@PathVariable Long flashSaleId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        FlashOrderVO vo = flashOrderService.purchase(flashSaleId, userId);
        // Phase 3: 返回 messageKey 让客户端轮询订单状态
        return ResultVO.success(Map.of(
                "status", "PROCESSING",
                "messageKey", vo.getMessageKey() != null ? vo.getMessageKey() : "",
                "flashSaleId", flashSaleId,
                "userId", userId
        ));
    }

    /**
     * 查询订单处理状态（Phase 3 新增）
     * <p>
     * 客户端用 purchase 返回的 messageKey 轮询此接口
     *
     * @param messageKey MQ 消息幂等键
     * @return "PROCESSING" 或 "DONE"
     */
    @GetMapping("/order/status")
    public ResultVO<Map<String, String>> orderStatus(@RequestParam String messageKey) {
        String status = flashOrderService.getOrderStatus(messageKey);
        return ResultVO.success(Map.of("status", status, "messageKey", messageKey));
    }

    @GetMapping("/order/list")
    public ResultVO<IPage<FlashOrder>> listUserOrders(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "10") long size,
                                                       Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResultVO.success(flashOrderService.listOrdersByUser(userId, page, size));
    }

    @GetMapping("/order/{id}")
    public ResultVO<FlashOrder> getOrder(@PathVariable Long id) {
        return ResultVO.success(flashOrderService.getOrderById(id));
    }

    @PostMapping("/order/{id}/pay")
    public ResultVO<Void> pay(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        flashOrderService.payOrder(id, userId);
        return ResultVO.success();
    }

    @PostMapping("/order/{id}/cancel")
    public ResultVO<Void> cancel(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        flashOrderService.cancelOrder(id, userId);
        return ResultVO.success();
    }

    @PostMapping("/order/{id}/refund")
    public ResultVO<Void> refund(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        flashOrderService.refundOrder(id, userId);
        return ResultVO.success();
    }

    @DeleteMapping("/order/{id}")
    public ResultVO<Void> deleteOrder(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        flashOrderService.deleteOrder(id, userId);
        return ResultVO.success();
    }
}
