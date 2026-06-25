package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.model.entity.Item;
import com.flashsale.service.ItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 商品 Service 实现
 *
 * <p>多级缓存：
 * <ul>
 *   <li>L1 Caffeine（TTL 120s）</li>
 *   <li>L2 Redis（TTL 24h）</li>
 *   <li>DB 回源</li>
 * </ul>
 */
@Service
public class ItemServiceImpl implements ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemServiceImpl.class);

    private final ItemMapper itemMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> itemCache;

    public ItemServiceImpl(ItemMapper itemMapper,
                           StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper,
                           Cache<String, String> itemCache) {
        this.itemMapper = itemMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.itemCache = itemCache;
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
        evictCache(item.getId());
        return item;
    }

    @Override
    public void deleteItem(Long id) {
        Item existing = itemMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        itemMapper.deleteById(id);
        evictCache(id);
    }

    @Override
    public Item getItemById(Long id) {
        String caffeineKey = "item:" + id;
        String redisKey = RedisConstants.ITEM_CACHE_KEY + id;

        // 1. L1 Caffeine
        String cachedJson = itemCache.getIfPresent(caffeineKey);
        if (cachedJson != null) {
            try {
                return objectMapper.readValue(cachedJson, Item.class);
            } catch (Exception e) {
                log.warn("[Caffeine] 商品反序列化失败, id={}, error={}", id, e.getMessage());
            }
        }

        // 2. L2 Redis
        String redisJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (redisJson != null) {
            try {
                Item item = objectMapper.readValue(redisJson, Item.class);
                itemCache.put(caffeineKey, redisJson);
                return item;
            } catch (Exception e) {
                log.warn("[Redis] 商品反序列化失败, id={}, error={}", id, e.getMessage());
                stringRedisTemplate.delete(redisKey);
            }
        }

        // 3. DB 回源
        Item item = itemMapper.selectById(id);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }

        // 4. 回填两级缓存
        try {
            String json = objectMapper.writeValueAsString(item);
            itemCache.put(caffeineKey, json);
            stringRedisTemplate.opsForValue().set(redisKey, json,
                    RedisConstants.ITEM_CACHE_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[缓存回填] 商品写入失败, id={}, error={}", id, e.getMessage());
        }

        return item;
    }

    @Override
    public IPage<Item> listItems(long page, long size) {
        LambdaQueryWrapper<Item> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Item::getCreateTime);
        return itemMapper.selectPage(new Page<>(page, size), wrapper);
    }

    private void evictCache(Long itemId) {
        String key = "item:" + itemId;
        itemCache.invalidate(key);
        stringRedisTemplate.delete(RedisConstants.ITEM_CACHE_KEY + itemId);
    }
}
