package com.flashsale.service;

import com.flashsale.model.vo.DashboardStatsVO;

/**
 * 管理端控制台统计 Service.
 * <p>聚合多表数据为单次查询即可渲染的统计总览，供 B 端首页使用。
 * 数据为实时直查 DB（含缓存/异步下单已落库数据），未做缓存——统计口径要求一致性。
 */
public interface StatsService {

    /**
     * 统计总览：KPI 汇总 + 近 7 日订单趋势 + 订单状态分布。
     *
     * @return 控制台统计总览
     */
    DashboardStatsVO overview();
}
