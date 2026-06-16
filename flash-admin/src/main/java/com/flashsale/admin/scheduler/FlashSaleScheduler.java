package com.flashsale.admin.scheduler;

import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.service.FlashSaleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀活动状态自动流转定时任务
 * <p>
 * 每分钟执行一次：
 * 1. 待开始 → 进行中（到达开始时间，触发 Redis 缓存预热）
 * 2. 进行中 → 已结束（已过结束时间）
 */
@Component
public class FlashSaleScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleScheduler.class);

    private final FlashSaleService flashSaleService;

    public FlashSaleScheduler(FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @Scheduled(fixedRate = 60_000)
    public void transitionFlashSaleStatus() {
        activatePendingSales();
        endExpiredSales();
    }

    private void activatePendingSales() {
        List<FlashSale> pendingSales = flashSaleService.getPendingSalesReadyToStart();
        if (pendingSales.isEmpty()) {
            return;
        }
        log.info("[状态流转] 发现 {} 个待激活的秒杀活动", pendingSales.size());
        for (FlashSale sale : pendingSales) {
            try {
                flashSaleService.updateStatus(sale.getId(), FlashSaleStatusEnum.ACTIVE.getCode());
                log.info("[状态流转] 秒杀活动已激活, id={}", sale.getId());
            } catch (Exception e) {
                log.error("[状态流转] 激活失败, id={}, error={}", sale.getId(), e.getMessage());
            }
        }
    }

    private void endExpiredSales() {
        List<FlashSale> activeSales = flashSaleService.getActiveSalesReadyToEnd();
        if (activeSales.isEmpty()) {
            return;
        }
        log.info("[状态流转] 发现 {} 个待结束的秒杀活动", activeSales.size());
        for (FlashSale sale : activeSales) {
            try {
                flashSaleService.updateStatus(sale.getId(), FlashSaleStatusEnum.ENDED.getCode());
                log.info("[状态流转] 秒杀活动已结束, id={}", sale.getId());
            } catch (Exception e) {
                log.error("[状态流转] 结束失败, id={}, error={}", sale.getId(), e.getMessage());
            }
        }
    }
}
