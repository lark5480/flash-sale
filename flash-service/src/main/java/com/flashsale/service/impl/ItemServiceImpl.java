package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.entity.Item;
import com.flashsale.model.enums.FlashSaleStatusEnum;
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
    private final FlashSaleMapper flashSaleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> itemCache;
    private final CacheInvalidatePublisher cacheInvalidatePublisher;

    public ItemServiceImpl(ItemMapper itemMapper,
                           FlashSaleMapper flashSaleMapper,
                           StringRedisTemplate stringRedisTemplate,
                           ObjectMapper objectMapper,
                           @Qualifier("itemCache") Cache<String, String> itemCache,
                           CacheInvalidatePublisher cacheInvalidatePublisher) {
        this.itemMapper = itemMapper;
        this.flashSaleMapper = flashSaleMapper;
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
        if (item == null || item.getId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "item id is required");
        }
        Item existing = itemMapper.selectById(item.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        // 上架商品不可直接编辑：上架商品可能正被待开始/进行中的秒杀活动引用并展示在 C 端货架，
        // 直接改名/图/价会造成售卖中商品资料突变。平台惯例：修改商品资料需先下架。
        if (Integer.valueOf(1).equals(existing.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "上架商品不可直接编辑，请先下架后再修改（如被秒杀活动引用，请先在秒杀管理中取消相关活动）");
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
        if (Integer.valueOf(1).equals(existing.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上架商品不可删除，请先下架后再删除");
        }
        // 商品被任何秒杀场次引用（含已结束/已取消的历史场次）时禁止删除：
        // 场次管理页与历史数据需实时回显商品资料，删除会造成悬空引用
        Long referencingSales = flashSaleMapper.selectCount(new LambdaQueryWrapper<FlashSale>()
                .eq(FlashSale::getItemId, id));
        if (referencingSales != null && referencingSales > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "该商品已被秒杀活动引用，无法删除（可选择下架停用该商品）");
        }
        itemMapper.deleteById(id);
        evictCache(id);
    }

    @Override
    public void changeStatus(Long id, Integer status) {
        if (status == null || (!status.equals(0) && !status.equals(1))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "status must be 0(下架) or 1(上架)");
        }
        Item existing = itemMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "item not found");
        }
        // 下架拦截：商品仍被待开始/进行中的秒杀活动引用时不允许下架，
        // 避免出现「秒杀货架还在售卖已下架商品」的中间态
        if (status == 0) {
            Long referencingSales = flashSaleMapper.selectCount(new LambdaQueryWrapper<FlashSale>()
                    .eq(FlashSale::getItemId, id)
                    .in(FlashSale::getStatus, FlashSaleStatusEnum.PENDING.getCode(),
                            FlashSaleStatusEnum.ACTIVE.getCode()));
            if (referencingSales != null && referencingSales > 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "该商品存在待开始或进行中的秒杀活动，请先取消相关活动后再下架");
            }
        }
        Item update = new Item();
        update.setId(id);
        update.setStatus(status);
        itemMapper.updateById(update);
        evictCache(id);
        log.info("[商品] 状态变更, id={}, status={}", id, status);
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
