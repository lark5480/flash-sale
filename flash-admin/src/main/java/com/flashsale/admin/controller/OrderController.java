package com.flashsale.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.service.FlashOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/order")
public class OrderController {

    private final FlashOrderService flashOrderService;

    public OrderController(FlashOrderService flashOrderService) {
        this.flashOrderService = flashOrderService;
    }

    @GetMapping("/list")
    public ResultVO<IPage<FlashOrder>> list(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return ResultVO.success(flashOrderService.listAllOrders(page, size));
    }

    @GetMapping("/{id}")
    public ResultVO<FlashOrder> detail(@PathVariable Long id) {
        return ResultVO.success(flashOrderService.getOrderById(id));
    }

    @PostMapping("/{id}/pay")
    public ResultVO<Void> pay(@PathVariable Long id) {
        flashOrderService.payOrder(id);
        return ResultVO.success();
    }

    @PostMapping("/{id}/refund")
    public ResultVO<Void> refund(@PathVariable Long id) {
        flashOrderService.refundOrder(id);
        return ResultVO.success();
    }
}
