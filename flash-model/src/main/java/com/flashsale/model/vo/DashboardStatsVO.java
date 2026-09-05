package com.flashsale.model.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端控制台统计总览 VO.
 * <p>由 {@code /admin/stats/overview} 一次聚合返回，供首页 KPI 卡片、趋势图与状态分布渲染。
 * 金额口径统一为「已支付（status=1）」的订单，未支付/取消/退款不计入成交额。
 */
public class DashboardStatsVO {

    /** 今日下单总数 */
    private long todayOrderCount;

    /** 今日已支付订单数 */
    private long todayPaidCount;

    /** 今日成交额（已支付订单金额合计），默认 0 */
    private BigDecimal todayPaidAmount = BigDecimal.ZERO;

    /** 当前待支付订单总数（含历史） */
    private long pendingPaymentOrderCount;

    /** 进行中秒杀场次数 */
    private long activeFlashSaleCount;

    /** 待开始秒杀场次数 */
    private long pendingFlashSaleCount;

    /** 商品总数（未删除） */
    private long itemCount;

    /** 上架商品数 */
    private long onSaleItemCount;

    /** 注册用户数（未删除） */
    private long userCount;

    /** 近 7 日订单趋势（日期升序，缺单日由 service 补零） */
    private List<DailyOrderStat> trend;

    /** 订单状态分布（0~3 全量补零） */
    private List<StatusCount> statusDist;

    public long getTodayOrderCount() {
        return todayOrderCount;
    }

    public void setTodayOrderCount(long todayOrderCount) {
        this.todayOrderCount = todayOrderCount;
    }

    public long getTodayPaidCount() {
        return todayPaidCount;
    }

    public void setTodayPaidCount(long todayPaidCount) {
        this.todayPaidCount = todayPaidCount;
    }

    public BigDecimal getTodayPaidAmount() {
        return todayPaidAmount;
    }

    public void setTodayPaidAmount(BigDecimal todayPaidAmount) {
        this.todayPaidAmount = todayPaidAmount != null ? todayPaidAmount : BigDecimal.ZERO;
    }

    public long getPendingPaymentOrderCount() {
        return pendingPaymentOrderCount;
    }

    public void setPendingPaymentOrderCount(long pendingPaymentOrderCount) {
        this.pendingPaymentOrderCount = pendingPaymentOrderCount;
    }

    public long getActiveFlashSaleCount() {
        return activeFlashSaleCount;
    }

    public void setActiveFlashSaleCount(long activeFlashSaleCount) {
        this.activeFlashSaleCount = activeFlashSaleCount;
    }

    public long getPendingFlashSaleCount() {
        return pendingFlashSaleCount;
    }

    public void setPendingFlashSaleCount(long pendingFlashSaleCount) {
        this.pendingFlashSaleCount = pendingFlashSaleCount;
    }

    public long getItemCount() {
        return itemCount;
    }

    public void setItemCount(long itemCount) {
        this.itemCount = itemCount;
    }

    public long getOnSaleItemCount() {
        return onSaleItemCount;
    }

    public void setOnSaleItemCount(long onSaleItemCount) {
        this.onSaleItemCount = onSaleItemCount;
    }

    public long getUserCount() {
        return userCount;
    }

    public void setUserCount(long userCount) {
        this.userCount = userCount;
    }

    public List<DailyOrderStat> getTrend() {
        return trend;
    }

    public void setTrend(List<DailyOrderStat> trend) {
        this.trend = trend;
    }

    public List<StatusCount> getStatusDist() {
        return statusDist;
    }

    public void setStatusDist(List<StatusCount> statusDist) {
        this.statusDist = statusDist;
    }

    /**
     * 单日订单统计（复用为「今日汇总」返回值时 date 为空）。
     */
    public static class DailyOrderStat {

        /** 日期 yyyy-MM-dd */
        private String date;

        /** 当日下单总数 */
        private long orderCount;

        /** 当日已支付订单数 */
        private long paidCount;

        /** 当日成交额（已支付金额合计） */
        private BigDecimal paidAmount = BigDecimal.ZERO;

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public long getOrderCount() {
            return orderCount;
        }

        public void setOrderCount(long orderCount) {
            this.orderCount = orderCount;
        }

        public long getPaidCount() {
            return paidCount;
        }

        public void setPaidCount(long paidCount) {
            this.paidCount = paidCount;
        }

        public BigDecimal getPaidAmount() {
            return paidAmount;
        }

        public void setPaidAmount(BigDecimal paidAmount) {
            this.paidAmount = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        }
    }

    /**
     * 订单状态计数（status 与 OrderStatusEnum.code 对应）。
     */
    public static class StatusCount {

        /** 订单状态码：0=待支付/1=已支付/2=已取消/3=已退款 */
        private Integer status;

        /** 该状态下订单数 */
        private long count;

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }
}
