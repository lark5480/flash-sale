package com.flashsale.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.vo.FlashSaleVO;
import com.flashsale.service.FlashSaleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin/flash-sale")
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    public FlashSaleController(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @PostMapping
    public ResultVO<FlashSale> create(@RequestBody FlashSale flashSale) {
        return ResultVO.success(flashSaleService.createFlashSale(flashSale));
    }

    @PutMapping
    public ResultVO<FlashSale> update(@RequestBody FlashSale flashSale) {
        return ResultVO.success(flashSaleService.updateFlashSale(flashSale));
    }

    @DeleteMapping("/{id}")
    public ResultVO<Void> delete(@PathVariable Long id) {
        flashSaleService.deleteFlashSale(id);
        return ResultVO.success();
    }

    @GetMapping("/list")
    public ResultVO<IPage<FlashSale>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Integer status) {
        return ResultVO.success(flashSaleService.listFlashSales(page, size, status));
    }

    @GetMapping("/{id}")
    public ResultVO<FlashSaleVO> detail(@PathVariable Long id) {
        return ResultVO.success(flashSaleService.getDetailWithItem(id));
    }

    @PutMapping("/{id}/status")
    public ResultVO<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        flashSaleService.updateStatus(id, body.get("status"));
        return ResultVO.success();
    }
}
