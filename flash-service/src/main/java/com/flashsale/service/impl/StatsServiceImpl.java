package com.flashsale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.mapper.FlashOrderMapper;
import com.flashsale.mapper.FlashSaleMapper;
import com.flashsale.mapper.ItemMapper;
import com.flashsale.mapper.UserMapper;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.entity.Item;
import com.flashsale.model.entity.User;
import com.flashsale.model.enums.FlashSaleStatusEnum;
import com.flashsale.model.enums.OrderStatusEnum;
import com.flashsale.model.vo.DashboardStatsVO;
import com.flashsale.model.vo.DashboardStatsVO.DailyOrderStat;
import com.flashsale.model.vo.DashboardStatsVO.StatusCount;
import com.flashsale.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台统计 Service 实现.
 * <p>统计口径说明：
 * <ul>
 *   <li>成交额 = 状态为「已支付」的订单金额合计，不含待支付/取消/退款订单；</li>
 *   <li>近 7 日趋势与状态分布缺省日期/状态由本类补零，保证前端拿到固定长度的连续序列；</li>
 *   <li>所有查询均经过 MyBatis-Plus 逻辑删除过滤（is_deleted = 0）。</li>
 * </ul>
 */
@Service
public class StatsServiceImpl implements StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsServiceImpl.class);

    private final FlashOrderMapper flashOrderMapper;
    private final FlashSaleMapper flashSaleMapper;
    private final ItemMapper itemMapper;
    private final UserMapper userMapper;

    public StatsServiceImpl(FlashOrderMapper flashOrderMapper,
                            FlashSaleMapper flashSaleMapper,
                            ItemMapper itemMapper,
                            UserMapper userMapper) {
        this.flashOrderMapper = flashOrderMapper;
        this.flashSaleMapper = flashSaleMapper;
        this.itemMapper = itemMapper;
        this.userMapper = userMapper;
    }

    @Override
    public DashboardStatsVO overview() {
        DashboardStatsVO vo = new DashboardStatsVO();
        LocalDate today = LocalDate.now();

        // ===== 今日订单汇总 =====
        DailyOrderStat todaySummary = flashOrderMapper.selectTodaySummary(today.atStartOfDay());
        if (todaySummary != null) {
            vo.setTodayOrderCount(todaySummary.getOrderCount());
            vo.setTodayPaidCount(todaySummary.getPaidCount());
            vo.setTodayPaidAmount(todaySummary.getPaidAmount());
        }

        // ===== 待支付订单（含历史，提醒及时处理） =====
        vo.setPendingPaymentOrderCount(flashOrderMapper.selectCount(
                new LambdaQueryWrapper<FlashOrder>()
                        .eq(FlashOrder::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())));

        // ===== 秒杀场次 =====
        vo.setActiveFlashSaleCount(flashSaleMapper.selectCount(
                new LambdaQueryWrapper<FlashSale>()
                        .eq(FlashSale::getStatus, FlashSaleStatusEnum.ACTIVE.getCode())));
        vo.setPendingFlashSaleCount(flashSaleMapper.selectCount(
                new LambdaQueryWrapper<FlashSale>()
                        .eq(FlashSale::getStatus, FlashSaleStatusEnum.PENDING.getCode())));

        // ===== 商品 / 用户 =====
        vo.setItemCount(itemMapper.selectCount(new LambdaQueryWrapper<Item>()));
        vo.setOnSaleItemCount(itemMapper.selectCount(
                new LambdaQueryWrapper<Item>().eq(Item::getStatus, 1)));
        vo.setUserCount(userMapper.selectCount(new LambdaQueryWrapper<User>()));

        // ===== 近 7 日趋势（补零至完整 7 天，日期升序） =====
        List<DailyOrderStat> trend = flashOrderMapper.selectDailyTrend(
                today.minusDays(6).atStartOfDay());
        vo.setTrend(fillDailyTrend(trend, today));

        // ===== 订单状态分布（0~3 全量补零） =====
        vo.setStatusDist(fillStatusDistribution(flashOrderMapper.selectStatusDistribution()));

        log.info("[控制台] 统计总览已生成, todayOrders={}, todayAmount={}, trendDays={}",
                vo.getTodayOrderCount(), vo.getTodayPaidAmount(),
                vo.getTrend() == null ? 0 : vo.getTrend().size());
        return vo;
    }

    /**
     * 将查询到的趋势行展开为以今天为终点的连续 7 天，缺失日期补零。
     */
    private List<DailyOrderStat> fillDailyTrend(List<DailyOrderStat> queried, LocalDate today) {
        Map<String, DailyOrderStat> byDate = new HashMap<>();
        if (queried != null) {
            for (DailyOrderStat stat : queried) {
                if (stat.getDate() != null) {
                    byDate.put(stat.getDate(), stat);
                }
            }
        }
        List<DailyOrderStat> result = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            String dateStr = today.minusDays(i).toString();
            DailyOrderStat stat = byDate.get(dateStr);
            if (stat == null) {
                stat = new DailyOrderStat();
                stat.setDate(dateStr);
                stat.setOrderCount(0L);
                stat.setPaidCount(0L);
                stat.setPaidAmount(BigDecimal.ZERO);
            }
            result.add(stat);
        }
        return result;
    }

    /**
     * 将查询到的状态分布补齐为 0~3 四个状态的全集，缺失状态补零。
     */
    private List<StatusCount> fillStatusDistribution(List<StatusCount> queried) {
        Map<Integer, Long> countByStatus = new HashMap<>();
        if (queried != null) {
            for (StatusCount item : queried) {
                if (item.getStatus() != null) {
                    countByStatus.put(item.getStatus(), item.getCount());
                }
            }
        }
        List<StatusCount> result = new ArrayList<>(4);
        for (int code = 0; code <= 3; code++) {
            StatusCount item = new StatusCount();
            item.setStatus(code);
            item.setCount(countByStatus.getOrDefault(code, 0L));
            result.add(item);
        }
        return result;
    }
}
