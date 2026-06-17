package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀活动 Service 实现
 * <p>
 * Phase 2：激活秒杀时自动预热 Redis 缓存（库存 + 详情）
 */
@Service
public class FlashSaleServiceImpl implements FlashSaleService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleServiceImpl.class);

    private final FlashSaleMapper flashSaleMapper;
    private final ItemMapper itemMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public FlashSaleServiceImpl(FlashSaleMapper flashSaleMapper,
                                ItemMapper itemMapper,
                                StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper) {
        this.flashSaleMapper = flashSaleMapper;
        this.itemMapper = itemMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public FlashSale createFlashSale(FlashSale flashSale) {
        // 新建秒杀活动，默认状态为「待开始」
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
        // 删除 Redis 缓存，下次 purchase 时从 DB 重新加载
        stringRedisTemplate.delete(RedisConstants.FLASH_STOCK_KEY + flashSale.getId());
        stringRedisTemplate.delete(RedisConstants.FLASH_SALE_KEY + flashSale.getId());
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
        stringRedisTemplate.delete(RedisConstants.FLASH_STOCK_KEY + id);
        stringRedisTemplate.delete(RedisConstants.FLASH_SALE_KEY + id);
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
        // 查询当前时间在 startTime ~ endTime 之间且状态为 ACTIVE 的秒杀活动
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

    /**
     * 修改秒杀活动状态
     * <p>
     * 当状态改为 ACTIVE（进行中）时，触发 Redis 缓存预热：
     * 1. 将库存写入 Redis Hash（flash:stock:{id}）
     * 2. 将活动详情 + 商品信息缓存到 Redis（flash:sale:{id}）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        FlashSale flashSale = flashSaleMapper.selectById(id);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        flashSale.setStatus(status);
        flashSaleMapper.updateById(flashSale);
        log.info("[秒杀活动] 状态变更, id={}, newStatus={}", id, status);

        // 激活秒杀 → Redis 缓存预热
        if (status.equals(FlashSaleStatusEnum.ACTIVE.getCode())) {
            warmUpCache(flashSale);
        }
    }

    /**
     * 缓存预热：将秒杀活动详情和库存加载到 Redis
     * <p>
     * 预热后，秒杀下单流程可以直接命中 Redis，避免每次请求都查 DB
     */
    private void warmUpCache(FlashSale flashSale) {
        Long saleId = flashSale.getId();
        try {
            // 1. 缓存库存
            String stockKey = RedisConstants.FLASH_STOCK_KEY + saleId;
            stringRedisTemplate.opsForValue().set(stockKey,
                    String.valueOf(flashSale.getStock()),
                    RedisConstants.FLASH_CACHE_TTL,
                    TimeUnit.SECONDS);

            // 2. 缓存秒杀详情（用于详情页快速查询）
            String saleKey = RedisConstants.FLASH_SALE_KEY + saleId;
            FlashSaleVO vo = buildFlashSaleVO(flashSale);
            stringRedisTemplate.opsForValue().set(saleKey,
                    objectMapper.writeValueAsString(vo),
                    RedisConstants.FLASH_CACHE_TTL,
                    TimeUnit.SECONDS);

            log.info("[缓存预热] 秒杀活动数据已加载到 Redis, id={}, stock={}", saleId, flashSale.getStock());
        } catch (Exception e) {
            // 预热失败不阻塞主流程，后续请求会回源 DB
            log.warn("[缓存预热] 写入 Redis 失败, id={}, error={}", saleId, e.getMessage());
        }
    }

    @Override
    public FlashSaleVO getDetailWithItem(Long id) {
        // 优先从 Redis 缓存读取
        String saleKey = RedisConstants.FLASH_SALE_KEY + id;
        String cached = stringRedisTemplate.opsForValue().get(saleKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, FlashSaleVO.class);
            } catch (JsonProcessingException e) {
                log.warn("[缓存读取] 反序列化失败，回源 DB, id={}, error={}", id, e.getMessage());
            }
        }

        // 缓存未命中，走 DB
        FlashSale flashSale = flashSaleMapper.selectById(id);
        if (flashSale == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        return buildFlashSaleVO(flashSale);
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

    /**
     * 构建带商品信息的 FlashSaleVO
     */
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
