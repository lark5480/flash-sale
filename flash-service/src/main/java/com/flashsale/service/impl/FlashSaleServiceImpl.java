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
import com.flashsale.service.config.CacheInvalidatePublisher;
import com.flashsale.service.message.CacheInvalidateMessage;
import com.flashsale.service.stock.FlashStockState;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;

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
    private final CacheInvalidatePublisher cacheInvalidatePublisher;
    private final FlashStockState flashStockState;

    public FlashSaleServiceImpl(FlashSaleMapper flashSaleMapper,
                                ItemMapper itemMapper,
                                StringRedisTemplate stringRedisTemplate,
                                ObjectMapper objectMapper,
                                @Qualifier("flashSaleDetailCache") Cache<String, String> flashSaleDetailCache,
                                @Qualifier("activeFlashSaleCache") Cache<String, String> activeFlashSaleCache,
                                CacheInvalidatePublisher cacheInvalidatePublisher,
                                FlashStockState flashStockState) {
        this.flashSaleMapper = flashSaleMapper;
        this.itemMapper = itemMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.flashSaleDetailCache = flashSaleDetailCache;
        this.activeFlashSaleCache = activeFlashSaleCache;
        this.cacheInvalidatePublisher = cacheInvalidatePublisher;
        this.flashStockState = flashStockState;
    }

    @Override
    public FlashSale createFlashSale(FlashSale flashSale) {
        checkItemOnSale(flashSale.getItemId());
        validateTimeRange(flashSale);
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
        validateTimeRange(flashSale);
        checkEditAllowed(existing, flashSale);
        checkStockEditable(existing, flashSale.getStock());
        // 仅换品时校验新商品状态：历史活动所绑商品即使已下架，也不影响改价格/时间等其他字段
        if (flashSale.getItemId() != null
                && (existing.getItemId() == null || flashSale.getItemId().longValue() != existing.getItemId().longValue())) {
            checkItemOnSale(flashSale.getItemId());
        }
        flashSaleMapper.updateById(flashSale);
        evictCache(flashSale.getId());
        log.info("[秒杀活动] 更新成功, id={}", flashSale.getId());
        return flashSale;
    }

    /**
     * 按状态收敛可编辑范围：
     * <ul>
     *   <li>ENDED：归档终态，任何编辑都拒绝；</li>
     *   <li>ACTIVE：只允许改秒杀价 / 限购数量 / 结束时间。
     *       换品会让已售订单与货架展示脱节；改开始时间会制造「ACTIVE 但 startTime 在未来」
     *       的幽灵场次（不进入 C 端列表却占着状态），两者一并锁定。</li>
     *   <li>PENDING / CANCELLED：全字段可编辑（CANCELLED 借此修正后重新启用）。</li>
     * </ul>
     */
    private void checkEditAllowed(FlashSale existing, FlashSale incoming) {
        if (existing.getStatus() == null) {
            return;
        }
        if (existing.getStatus().equals(FlashSaleStatusEnum.ENDED.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "已结束的秒杀活动为归档数据，不可编辑");
        }
        if (existing.getStatus().equals(FlashSaleStatusEnum.ACTIVE.getCode())) {
            if (incoming.getItemId() != null
                    && (existing.getItemId() == null || !incoming.getItemId().equals(existing.getItemId()))) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "进行中的秒杀活动不可更换商品，如需调整请先取消活动");
            }
            if (incoming.getStartTime() != null && !incoming.getStartTime().equals(existing.getStartTime())) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "进行中的秒杀活动不可修改开始时间");
            }
        }
    }

    /** endTime 必须在 startTime 之后（两者都携带时才校验，单字段更新不受影响） */
    private void validateTimeRange(FlashSale flashSale) {
        if (flashSale.getStartTime() != null && flashSale.getEndTime() != null
                && !flashSale.getEndTime().isAfter(flashSale.getStartTime())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "结束时间必须晚于开始时间");
        }
    }

    /**
     * stock 列同时是「管理员可编辑字段」和「已成交库存台账」（deductStock / restoreStock 都在写它）。
     * 场次进行中时，后台表单里那个值很可能是打开页面那一刻的快照，全量 updateById 会把台账
     * 直接改回旧值或更大的值，等于凭空放出已卖出的库存。
     * 因此进行中的场次只允许改其他字段；要补库存须走「结束场次 → 改库存 → 重新激活」，
     * 让重新激活时按 DB stock − 在途 重建 Redis 状态键。
     * <p>
     * stock 为 null 表示本次请求没带这个字段（MyBatis-Plus updateById 会跳过 null），不算改动。
     */
    private void checkStockEditable(FlashSale existing, Integer newStock) {
        if (newStock == null || newStock.equals(existing.getStock())) {
            return;
        }
        if (existing.getStatus() != null && existing.getStatus().equals(FlashSaleStatusEnum.ACTIVE.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "进行中的秒杀活动不能修改库存，请先结束活动再调整。当前库存=" + existing.getStock()
                            + "，请求库存=" + newStock);
        }
    }

    /**
     * 校验秒杀活动绑定的商品必须存在且处于上架状态。
     * <p>下架商品不允许加入秒杀活动（创建或换品时校验），从源头保证
     * 「能上秒杀货架的商品一定是上架状态」。
     */
    private void checkItemOnSale(Long itemId) {
        if (itemId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "秒杀活动必须绑定商品");
        }
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在，无法加入秒杀活动");
        }
        if (!Integer.valueOf(1).equals(item.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品已下架，不能加入秒杀活动，请先上架该商品");
        }
    }

    @Override
    public void deleteFlashSale(Long id) {
        FlashSale existing = flashSaleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "flash sale not found");
        }
        // 进行中在售、已结束归档的活动删除会破坏台账，仅允许清理未开始/已取消的场次
        if (existing.getStatus() != null
                && (existing.getStatus().equals(FlashSaleStatusEnum.ACTIVE.getCode())
                        || existing.getStatus().equals(FlashSaleStatusEnum.ENDED.getCode()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "进行中或已结束的秒杀活动不可删除");
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
        FlashSaleStatusEnum current = FlashSaleStatusEnum.codeOf(flashSale.getStatus());
        FlashSaleStatusEnum target = FlashSaleStatusEnum.codeOf(status);
        // 状态机守卫：只放行枚举里定义的合法流转，脏请求/重复提交一律拦截
        if (current == null || target == null || !current.canTransitTo(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "非法状态流转：" + (current == null ? "未知" : current.getDesc())
                            + " → " + (target == null ? "未知" : target.getDesc()));
        }
        LocalDateTime now = LocalDateTime.now();
        // 重新启用已取消的活动（CANCELLED→PENDING），按场次时间自动恢复：
        // - 已过原定结束时间：拒绝，提示先编辑把时间改到未来；
        // - 开始时间已过（曾被开售、只是被中途停掉）：直接恢复为「进行中」，重新启用即恢复售卖，
        //   不走「待开始 → 定时任务扫描」的 ≤60s 停售空窗，下面 ACTIVE 分支会重建库存键并预热；
        // - 开始时间在未来（未开售就被取消）：回到「待开始」，到点后由定时任务自动开售。
        if (current == FlashSaleStatusEnum.CANCELLED && target == FlashSaleStatusEnum.PENDING) {
            if (flashSale.getEndTime() == null || !flashSale.getEndTime().isAfter(now)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "该活动已过原定结束时间，请先编辑调整开始/结束时间后再重新启用");
            }
            if (flashSale.getStartTime() != null && !flashSale.getStartTime().isAfter(now)) {
                target = FlashSaleStatusEnum.ACTIVE;
                status = FlashSaleStatusEnum.ACTIVE.getCode();
                log.info("[秒杀活动] 重新启用时开始时间已过，直接恢复为进行中, id={}", id);
            }
        }
        // 任何通向「待开始 / 进行中」的流转（手动启用、取消后重新启用、定时任务激活）都要求
        // 绑定商品处于上架状态，杜绝「取消活动 → 下架商品 → 重新启用 → 到点自动激活」旁路，
        // 防止下架商品复活重新登上秒杀货架。
        if (target == FlashSaleStatusEnum.PENDING || target == FlashSaleStatusEnum.ACTIVE) {
            Item item = itemMapper.selectById(flashSale.getItemId());
            if (item == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在，无法启用该秒杀活动");
            }
            if (!Integer.valueOf(1).equals(item.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "该活动绑定的商品已下架，请先上架商品（或在编辑中更换商品）后再启用");
            }
        }
        Integer previousStatus = flashSale.getStatus();
        flashSale.setStatus(status);
        flashSaleMapper.updateById(flashSale);
        log.info("[秒杀活动] 状态变更, id={}, newStatus={}", id, status);

        // 失效两级缓存
        evictCache(id);

        // 激活时预热 Redis 缓存
        int activeCode = FlashSaleStatusEnum.ACTIVE.getCode();
        if (status.equals(activeCode)) {
            // 从非活跃状态重新激活：上一周期遗留的库存键仍持有按旧 DB stock 算出的值，
            // ensureStockKey 看到键已存在会跳过重建，停售期间调大/调小的库存永远不会生效。
            // 非活跃状态没有购买流量，先删旧键再由 warmUpRedis 按新 DB stock − 在途 重建是安全的。
            if (previousStatus == null || previousStatus.intValue() != activeCode) {
                flashStockState.deleteStockKey(id);
            }
            warmUpRedis(flashSale);
        }
    }

    /**
     * 预热 Redis：库存状态键（仅缺失时补建）+ 活动详情缓存
     */
    private void warmUpRedis(FlashSale flashSale) {
        Long saleId = flashSale.getId();
        try {
            // 库存不是缓存而是业务状态：键已存在时绝不覆盖，缺失时按 DB stock − 在途 补建
            flashStockState.ensureStockKey(flashSale);

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
        stringRedisTemplate.delete(RedisConstants.ACTIVE_FLASH_SALE_LIST_KEY);

        // 故意不删 flash:stock:{id}：它是库存状态而非缓存，删掉等于把权威计数交给滞后的 DB 重建。
        // 库存键的生命周期只由「场次结束 + 宽限期」的 TTL 决定。

        // 广播缓存失效，通知其他节点 invalidate 各自的 Caffeine
        cacheInvalidatePublisher.publish(CacheInvalidateMessage.CACHE_FLASH_SALE_DETAIL, caffeineKey);
        cacheInvalidatePublisher.publish(CacheInvalidateMessage.CACHE_ACTIVE_FLASH_SALE, CacheInvalidateMessage.KEY_ALL);
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
