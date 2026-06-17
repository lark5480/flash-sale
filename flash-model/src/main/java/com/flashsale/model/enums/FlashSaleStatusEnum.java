package com.flashsale.model.enums;

/**
 * 秒杀活动状态枚举.
 * <p>
 * 状态流转: PENDING → ACTIVE → ENDED；任意非 ENDED 状态可被置为 CANCELLED.
 * 数据库 flash_sale.status 字段存 code 值.
 */
public enum FlashSaleStatusEnum {

    /** 待开始——活动已创建但未到 startTime */
    PENDING(0, "pending"),

    /** 进行中——已激活，Redis 缓存已预热，用户可下单 */
    ACTIVE(1, "active"),

    /** 已结束——超过 endTime 自动切换，不可再下单 */
    ENDED(2, "ended"),

    /** 已取消——人工干预终止 */
    CANCELLED(3, "cancelled");

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
}
