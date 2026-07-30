package com.flashsale.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.constant.RedisConstants;
import com.flashsale.common.exception.BusinessException;
import com.flashsale.common.result.ResultCode;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.entity.Item;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.model.vo.FlashSaleVO;
import com.flashsale.service.FlashSaleService;
import com.github.benmanes.caffeine.cache.Cache;

/**
 * 秒杀活动 Service 实现
 *
 * <p>多级缓存策略：
 * <ul>
 *   <li>L1 Caffeine（本地、毫秒级、短 TTL）</li>
 *   <li>L2 Redis（分布式、ms 级、长 TTL）</li>
 *   <li>DB 回源兜底</li>
 * </ul>
 * 写操作同时失效两级缓存，下一个读请求重新回源。
 */
@Service
public class FlashSaleServiceImpl implements FlashSaleService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleServiceImpl.class);

    private final FlashSaleMapper flashSaleMapper;
    private final ItemMapper itemMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> flashSaleDetailCache;
    private final Cache<String, String> activeFlashSaleCache;

    public FlashSaleServiceImpl(FlashSaleMapper flashSaleMapper,
                                ItemMapper itemMapper,
                                StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper,
                                Cache<String, String> flashSaleDetailCache,
                                Cache<String, String> activeFlashSaleCache) {
        this.flashSaleMapper = flashSaleMapper;
        this.itemMapper = itemMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.flashSaleDetailCache = flashSaleDetailCache;
        this.activeFlashSaleCache = activeFlashSaleCache;
    }

    @Override
    public FlashSale createFlashSale(FlashSale flashSale) {
        flashSale.setStatus(FlashSaleStatusEnum.PENDING.getCode());
        flashSaleMapper.insert(flashSale);
        log.info("[秒杀活动] 创建成功, id={}, itemId={}, flashPrice={}, stock={}",
                flashSale.getId(), flashSale.getItemId(), flashSale.getFlashPrice(), flashSale.getStock());
        return flashSale;
    }

    @Override
    public FlashSale updateFlashSale(FlashSale flashSale) {
        FlashSale existing = flashSaleMapper.selectById(flashSale.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        flashSaleMapper.updateById(flashSale);
        evictCache(flashSale.getId());
        log.info("[秒杀活动] 更新成功, id={}", flashSale.getId());
        return flashSale;
    }

    @Override
    public void deleteFlashSale(Long id) {
        FlashSale existing = flashSaleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        flashSaleMapper.deleteById(id);
        evictCache(id);
        log.info("[秒杀活动] 删除成功, id={}", id);
    }

    @Override
    public FlashSale getFlashSaleById(Long id) {
        FlashSale flashSale = flashSaleMapper.selectById(id);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        return flashSale;
    }

    @Override
    public List<FlashSaleVO> getActiveFlashSales() {
        String cacheKey = RedisConstants.ACTIVE_FLASH_SALE_LIST_KEY;
        String redisKey = RedisConstants.ACTIVE_FLASH_SALE_LIST_KEY;

        try {
            // 使用 Caffeine get(key, function) 实现 per-key 同步，防止缓存击穿
            String json = activeFlashSaleCache.get(cacheKey, key -> {
                // L2 Redis
                String redisJson = stringRedisTemplate.opsForValue().get(redisKey);
                if (redisJson != null) {
                    return redisJson;
                }

                // L3 DB 回源
                List<FlashSaleVO> result = loadActiveFlashSalesFromDb();
                String resultJson;
                try {
                    resultJson = objectMapper.writeValueAsString(result);
                } catch (Exception e) {
                    throw new RuntimeException("JSON序列化失败", e);
                }

                // 回填 Redis
                stringRedisTemplate.opsForValue().set(redisKey, resultJson,
                        RedisConstants.randomTtl(30), TimeUnit.SECONDS);

                return resultJson;
            });

            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FlashSaleVO.class));
        } catch (Exception e) {
            log.error("[缓存] getActiveFlashSales 异常, error={}", e.getMessage(), e);
            // 降级：直接查 DB
            return loadActiveFlashSalesFromDb();
        }
    }

    private List<FlashSaleVO> loadActiveFlashSalesFromDb() {
        LambdaQueryWrapper<FlashSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashSale::getStatus, FlashSaleStatusEnum.ACTIVE.getCode())
                .le(FlashSale::getStartTime, LocalDateTime.now())
                .ge(FlashSale::getEndTime, LocalDateTime.now())
                .orderByAsc(FlashSale::getStartTime);
        List<FlashSale> list = flashSaleMapper.selectList(wrapper);
        return list.stream().map(this::buildFlashSaleVO).collect(Collectors.toList());
    }

    @Override
    public IPage<FlashSale> listFlashSales(long page, long size, Integer status) {
        LambdaQueryWrapper<FlashSale> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(FlashSale::getStatus, status);
        }
        wrapper.orderByDesc(FlashSale::getCreateTime);
        return flashSaleMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        FlashSale flashSale = flashSaleMapper.selectById(id);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        flashSale.setStatus(status);
        flashSaleMapper.updateById(flashSale);
        log.info("[秒杀活动] 状态变更, id={}, newStatus={}", id, status);

        // 失效两级缓存
        evictCache(id);

        // 激活时预热 Redis 缓存
        if (status.equals(FlashSaleStatusEnum.ACTIVE.getCode())) {
            warmUpRedis(flashSale);
        }
    }

    /**
     * 预热 Redis 缓存：库存 + 详情
     */
    private void warmUpRedis(FlashSale flashSale) {
        Long saleId = flashSale.getId();
        try {
            String stockKey = RedisConstants.FLASH_STOCK_KEY + saleId;
            stringRedisTemplate.opsForValue().set(stockKey,
                    String.valueOf(flashSale.getStock()),
                    RedisConstants.randomTtl(RedisConstants.FLASH_CACHE_TTL),
                    TimeUnit.SECONDS);

            String saleKey = RedisConstants.FLASH_SALE_KEY + saleId;
            FlashSaleVO vo = buildFlashSaleVO(flashSale);
            stringRedisTemplate.opsForValue().set(saleKey,
                    objectMapper.writeValueAsString(vo),
                    RedisConstants.randomTtl(RedisConstants.FLASH_CACHE_TTL),
                    TimeUnit.SECONDS);

            log.info("[缓存预热] 秒杀活动数据已加载到 Redis, id={}, stock={}", saleId, flashSale.getStock());
        } catch (Exception e) {
            log.warn("[缓存预热] 写入 Redis 失败, id={}, error={}", saleId, e.getMessage());
        }
    }

    @Override
    public FlashSaleVO getDetailWithItem(Long id) {
        String caffeineKey = "detail:" + id;
        String redisKey = RedisConstants.FLASH_SALE_KEY + id;

        try {
            // 使用 Caffeine get(key, function) 实现 per-key 同步，防止缓存击穿
            String json = flashSaleDetailCache.get(caffeineKey, key -> {
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
                FlashSale flashSale = flashSaleMapper.selectById(id);
                if (flashSale == null) {
                    // 缓存穿透防护：写入空值标记到 Redis
                    stringRedisTemplate.opsForValue().set(redisKey, RedisConstants.CACHE_NULL,
                            RedisConstants.NULL_CACHE_TTL, TimeUnit.SECONDS);
                    return RedisConstants.CACHE_NULL;
                }
                FlashSaleVO vo = buildFlashSaleVO(flashSale);
                String voJson;
                try {
                    voJson = objectMapper.writeValueAsString(vo);
                } catch (Exception e) {
                    throw new RuntimeException("JSON序列化失败", e);
                }

                // 回填 Redis
                stringRedisTemplate.opsForValue().set(redisKey, voJson,
                        RedisConstants.randomTtl(RedisConstants.FLASH_CACHE_TTL),
                        TimeUnit.SECONDS);

                return voJson;
            });

            // 判断是否为空值标记
            if (RedisConstants.CACHE_NULL.equals(json)) {
                throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
            }

            return objectMapper.readValue(json, FlashSaleVO.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[缓存] getDetailWithItem 异常, id={}, error={}", id, e.getMessage(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    @Override
    public List<FlashSale> getPendingSalesReadyToStart() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<FlashSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashSale::getStatus, FlashSaleStatusEnum.PENDING.getCode())
                .le(FlashSale::getStartTime, now)
                .gt(FlashSale::getEndTime, now);
        return flashSaleMapper.selectList(wrapper);
    }

    @Override
    public List<FlashSale> getActiveSalesReadyToEnd() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<FlashSale> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FlashSale::getStatus, FlashSaleStatusEnum.ACTIVE.getCode())
                .le(FlashSale::getEndTime, now);
        return flashSaleMapper.selectList(wrapper);
    }

    // ============== 私有方法 ==============

    private void evictCache(Long saleId) {
        String caffeineKey = "detail:" + saleId;
        flashSaleDetailCache.invalidate(caffeineKey);
        activeFlashSaleCache.invalidate(RedisConstants.ACTIVE_FLASH_SALE_LIST_KEY);
        stringRedisTemplate.delete(RedisConstants.FLASH_SALE_KEY + saleId);
        stringRedisTemplate.delete(RedisConstants.FLASH_STOCK_KEY + saleId);
        stringRedisTemplate.delete(RedisConstants.ACTIVE_FLASH_SALE_LIST_KEY);
    }

    private FlashSaleVO buildFlashSaleVO(FlashSale flashSale) {
        FlashSaleVO vo = FlashSaleVO.from(flashSale);
        Item item = itemMapper.selectById(flashSale.getItemId());
        if (item != null) {
            vo.setItemName(item.getName());
            vo.setItemImage(item.getImage());
            vo.setOriginalPrice(item.getPrice());
        }
        return vo;
    }
}
