package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.entity.Item;

public interface ItemService {

    Item createItem(Item item);

    Item updateItem(Item item);

    void deleteItem(Long id);

    /**
     * 切换商品上下架状态（1=上架，0=下架），同时失效商品缓存
     */
    void changeStatus(Long id, Integer status);

    Item getItemById(Long id);

    IPage<Item> listItems(long page, long size);
}
