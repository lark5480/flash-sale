package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.entity.Item;

public interface ItemService {

    Item createItem(Item item);

    Item updateItem(Item item);

    void deleteItem(Long id);

    Item getItemById(Long id);

    IPage<Item> listItems(long page, long size);
}
