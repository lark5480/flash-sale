package com.flashsale.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.Item;
import com.flashsale.model.vo.ItemVO;
import com.flashsale.service.ItemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/item")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/list")
    public ResultVO<IPage<Item>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return ResultVO.success(itemService.listItems(page, size));
    }

    @GetMapping("/{id}")
    public ResultVO<ItemVO> detail(@PathVariable Long id) {
        Item item = itemService.getItemById(id);
        return ResultVO.success(ItemVO.from(item));
    }
}
