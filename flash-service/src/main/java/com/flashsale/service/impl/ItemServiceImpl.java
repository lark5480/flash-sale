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
import com.flashsale.service.config.CacheInvalidatePublisher;
import com.flashsale.service.message.CacheInvalidateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final CacheInvalidatePublisher cacheInvalidatePublisher;

    public ItemServiceImpl(ItemMapper itemMapper,
                           StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper,
                           @Qualifier("itemCache") Cache<String, String> itemCache,
                           CacheInvalidatePublisher cacheInvalidatePublisher) {
        this.itemMapper = itemMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.itemCache = itemCache;
        this.cacheInvalidatePublisher = cacheInvalidatePublisher;
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

        try {
            // 使用 Caffeine get(key, function) 实现 per-key 同步，防止缓存击穿
            String json = itemCache.get(caffeineKey, key -> {
                // L2 Redis
                String redisJson = stringRedisTemplate.opsForValue().get(redisKey);
                if (redisJson != null) {
                    // 缓存穿透防护：命中空值标记
                    if (RedisConstants.CACHE_NULL.equals(redisJson)) {
                        return RedisConstants.CACHE_NULL;
                    }
                    return redisJson;
                }

                // L3 DB 回源
                Item item = itemMapper.selectById(id);
                if (item == null) {
                    // 缓存穿透防护：写入空值标记到 Redis
                    stringRedisTemplate.opsForValue().set(redisKey, RedisConstants.CACHE_NULL,
                            RedisConstants.NULL_CACHE_TTL, TimeUnit.SECONDS);
                    return RedisConstants.CACHE_NULL;
                }

                String itemJson;
                try {
                    itemJson = objectMapper.writeValueAsString(item);
                } catch (Exception e) {
                    throw new RuntimeException("JSON序列化失败", e);
                }

                // 回填 Redis
                stringRedisTemplate.opsForValue().set(redisKey, itemJson,
                        RedisConstants.randomTtl(RedisConstants.ITEM_CACHE_TTL),
                        TimeUnit.SECONDS);

                return itemJson;
            });

            // 判断是否为空值标记
            if (RedisConstants.CACHE_NULL.equals(json)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
            }

            return objectMapper.readValue(json, Item.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[缓存] getItemById 异常, id={}, error={}", id, e.getMessage(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
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

        // 广播缓存失效，通知其他节点 invalidate 各自的 Caffeine
        cacheInvalidatePublisher.publish(CacheInvalidateMessage.CACHE_ITEM, key);
    }
}
