package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.model.entity.Item;
import com.flashsale.service.ItemService;
import org.springframework.stereotype.Service;

@Service
public class ItemServiceImpl implements ItemService {

    private final ItemMapper itemMapper;

    public ItemServiceImpl(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Override
    public Item createItem(Item item) {
        item.setStatus(1);
        itemMapper.insert(item);
        return item;
    }

    @Override
    public Item updateItem(Item item) {
        Item existing = itemMapper.selectById(item.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        itemMapper.updateById(item);
        return item;
    }

    @Override
    public void deleteItem(Long id) {
        Item existing = itemMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        itemMapper.deleteById(id);
    }

    @Override
    public Item getItemById(Long id) {
        Item item = itemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        return item;
    }

    @Override
    public IPage<Item> listItems(long page, long size) {
        LambdaQueryWrapper<Item> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Item::getCreateTime);
        return itemMapper.selectPage(new Page<>(page, size), wrapper);
    }
}
