package com.flashsale.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.annotation.RateLimit;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.vo.FlashOrderVO;
import com.flashsale.service.CaptchaService;
import com.flashsale.service.FlashOrderService;
import com.flashsale.service.metrics.FlashSaleMetrics;
import io.micrometer.core.instrument.Timer;
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

    /** 单页最大条数，防止 size 被恶意放大 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final FlashOrderService flashOrderService;
    private final CaptchaService captchaService;
    private final FlashSaleMetrics flashSaleMetrics;

    public FlashOrderController(FlashOrderService flashOrderService,
                                CaptchaService captchaService,
                                FlashSaleMetrics flashSaleMetrics) {
        this.flashOrderService = flashOrderService;
        this.captchaService = captchaService;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    /**
     * 秒杀下单 —— Phase 3 异步版本
     * <p>
     * 返回 messageKey 供客户端轮询订单状态
     */
    @RateLimit(key = "purchase", permits = 5, windowSeconds = 5)
    @PostMapping("/flash-sale/{flashSaleId}/purchase")
    public ResultVO<Map<String, Object>> purchase(
            @PathVariable Long flashSaleId,
            @RequestParam String captchaId,
            @RequestParam String captchaAnswer,
            Authentication auth) {
        if (!captchaService.validate(captchaId, captchaAnswer)) {
            return ResultVO.fail(ResultCode.CAPTCHA_ERROR, "验证码错误或已过期");
        }
        Long userId = (Long) auth.getPrincipal();
        Timer.Sample timerSample = flashSaleMetrics.startTimer();
        try {
            FlashOrderVO vo = flashOrderService.purchase(flashSaleId, userId);
            // Phase 3: 返回 messageKey 让客户端轮询订单状态
            return ResultVO.success(Map.of(
                    "status", "PROCESSING",
                    "messageKey", vo.getMessageKey() != null ? vo.getMessageKey() : "",
                    "flashSaleId", flashSaleId,
                    "userId", userId
            ));
        } finally {
            flashSaleMetrics.stopTimer(timerSample);
        }
    }

    /**
     * 查询订单处理状态（Phase 3 新增）
     * <p>
     * 客户端用 purchase 返回的 messageKey 轮询此接口
     *
     * @param messageKey MQ 消息幂等键
     * @return "PROCESSING" 处理中、"DONE" 订单已创建、"FAILED" 业务终态失败
     */
    @GetMapping("/order/status")
    public ResultVO<Map<String, String>> orderStatus(@RequestParam String messageKey) {
        String status = flashOrderService.getOrderStatus(messageKey);
        return ResultVO.success(Map.of("status", status, "messageKey", messageKey));
    }

    /**
     * 我的订单（分页 + 状态筛选 + 关键词搜索）
     *
     * @param page    页码，从 1 开始
     * @param size    每页条数，限制在 1~100，防止大分页拖垮数据库
     * @param status  订单状态（0待支付/1已支付/2已取消/3已退款），为空表示全部
     * @param keyword 关键词，匹配订单号或商品名称
     */
    @GetMapping("/order/list")
    public ResultVO<IPage<FlashOrderVO>> listUserOrders(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(required = false) String keyword,
                                                        Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        long pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ResultVO.success(flashOrderService.listOrdersByUser(userId, page, pageSize, status, keyword));
    }

    @GetMapping("/order/{id}")
    public ResultVO<FlashOrder> getOrder(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResultVO.success(flashOrderService.getOrderById(id, userId));
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
