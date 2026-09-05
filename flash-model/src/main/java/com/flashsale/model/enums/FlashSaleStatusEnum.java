package com.flashsale.model.enums;

/**
 * 秒杀活动状态枚举.
 * <p>
 * 状态流转（仅下表组合合法，其余一律拒绝）：
 * <pre>
 *   PENDING(0) --启用--> ACTIVE(1) --自然结束(定时任务)--> ENDED(2, 归档终态)
 *      ^   |                |
 *      |   | 取消            | 中途取消
 *      |   v                v
 *      +--- CANCELLED(3) &lt;-------
 *   重新启用(入口为 CANCELLED→PENDING 请求，service 按时间自动分派：
 *     开始时间已过 → 直接恢复 ACTIVE；未到开始时间 → 回 PENDING 排队)
 * </pre>
 * <ul>
 *   <li>人工流转：PENDING→ACTIVE（启用）、PENDING→CANCELLED（取消未开始）、
 *       ACTIVE→CANCELLED（中途终止）、CANCELLED→PENDING（重新启用入口，见下）</li>
 *   <li>重新启用（CANCELLED→PENDING 请求）由 service 按场次时间分派：startTime ≤ now 且
 *       endTime > now（曾被开售、只是被中途停掉）直接落到 ACTIVE 恢复售卖，消除定时扫描空窗；
 *       startTime > now（未开售即被取消）回到 PENDING，到点仍由定时任务自动开售</li>
 *   <li>ACTIVE→ENDED 仅由定时任务在 endTime 到达后触发，不提供管理端手动入口</li>
 *   <li>ENDED 为归档终态：只读，不再参与任何流转</li>
 * </ul>
 * 数据库 flash_sale.status 字段存 code 值.
 */
public enum FlashSaleStatusEnum {

    /** 待开始——活动已创建但未到 startTime（或取消场次重新启用后开始时间仍未到，处于排队） */
    PENDING(0, "待开始"),

    /** 进行中——已激活，Redis 缓存已预热，用户可下单 */
    ACTIVE(1, "进行中"),

    /** 已结束——超过 endTime 由定时任务切换，归档只读，不可再下单 */
    ENDED(2, "已结束"),

    /** 已取消——人工干预终止，可编辑修正后重新启用 */
    CANCELLED(3, "已取消");

    /** 数据库存储值 */
    private final int code;
    /** 状态描述 */
    private final String desc;

    FlashSaleStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 按存储值反查枚举，未知值返回 null */
    public static FlashSaleStatusEnum codeOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (FlashSaleStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 是否允许从当前状态流转到 target。
     * <p>ENDED 为归档终态返回 false；target 为 null 或与当前状态相同也返回 false，
     * 保证重复提交/直达 API 的脏请求在入口被拦截。
     */
    public boolean canTransitTo(FlashSaleStatusEnum target) {
        if (target == null) {
            return false;
        }
        switch (this) {
            case PENDING:
                return target == ACTIVE || target == CANCELLED;
            case ACTIVE:
                return target == CANCELLED || target == ENDED;
            case CANCELLED:
                return target == PENDING;
            default:
                // ENDED：归档终态，禁止任何出向流转
                return false;
        }
    }
}
