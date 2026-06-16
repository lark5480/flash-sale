package com.flashsale.api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flashsale.common.result.ResultVO;
import com.flashsale.model.vo.FlashSaleVO;
import com.flashsale.service.FlashSaleService;

@RestController
@RequestMapping("/api/flash-sale")
public class FlashSaleController {
    
    private final FlashSaleService flashSaleService;

    public FlashSaleController(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @GetMapping("/active")
    public ResultVO<List<FlashSaleVO>> getActive() {
        return ResultVO.success(flashSaleService.getActiveFlashSales());
    }

    @GetMapping("/{id}")
    public ResultVO<FlashSaleVO> detail(@PathVariable Long id) {
        return ResultVO.success(flashSaleService.getDetailWithItem(id));
    }
}
