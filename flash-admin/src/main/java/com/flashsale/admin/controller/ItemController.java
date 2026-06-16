package com.flashsale.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.common.result.ResultVO;
import com.flashsale.model.entity.Item;
import com.flashsale.model.vo.ItemVO;
import com.flashsale.service.ItemService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/item")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping
    public ResultVO<Item> create(@RequestBody Item item) {
        return ResultVO.success(itemService.createItem(item));
    }

    @PutMapping
    public ResultVO<Item> update(@RequestBody Item item) {
        return ResultVO.success(itemService.updateItem(item));
    }

    @DeleteMapping("/{id}")
    public ResultVO<Void> delete(@PathVariable Long id) {
        itemService.deleteItem(id);
        return ResultVO.success();
    }

    @GetMapping("/list")
    public ResultVO<IPage<Item>> list(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return ResultVO.success(itemService.listItems(page, size));
    }

    @GetMapping("/{id}")
    public ResultVO<ItemVO> detail(@PathVariable Long id) {
        return ResultVO.success(ItemVO.from(itemService.getItemById(id)));
    }
}
