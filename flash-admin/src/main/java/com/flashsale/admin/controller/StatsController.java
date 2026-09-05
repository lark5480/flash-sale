package com.flashsale.admin.controller;

import com.flashsale.common.result.ResultVO;
import com.flashsale.model.vo.DashboardStatsVO;
import com.flashsale.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端控制台统计接口.
 * <p>供 B 端首页渲染 KPI 卡片、近 7 日订单趋势与订单状态分布。
 * 访问权限：已登录管理员（AdminSecurityConfig 中 /admin/stats/** 走 anyRequest().authenticated()）。
 */
@RestController
@RequestMapping("/admin/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    public ResultVO<DashboardStatsVO> overview() {
        return ResultVO.success(statsService.overview());
    }
}
