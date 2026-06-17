package com.flashsale.model.enums;

/**
 * 订单状态枚举.
 * <p>
 * 状态流转:
 * <pre>
 * PENDING_PAYMENT ──┬──→ PAID ──→ REFUNDED
 *                   │
 *                   └──→ CANCELLED
 * </pre>
 * 数据库 flash_order.status 字段存 code 值.
 * 超时未支付的 PENDING_PAYMENT 订单由定时任务 {@code OrderScheduler} 批量取消.
 */
public enum OrderStatusEnum {

    /** 待支付——秒杀下单成功后的初始状态，默认 15 分钟内需完成支付 */
    PENDING_PAYMENT(0, "pending payment"),

    /** 已支付——用户完成支付 */
    PAID(1, "paid"),

    /** 已取消——超时未支付自动取消 或 用户主动取消，库存已归还 */
    CANCELLED(2, "cancelled"),

    /** 已退款——管理员操作退款，库存已归还 */
    REFUNDED(3, "refunded");

    /** 数据库存储值 */
    private final int code;
    /** 状态描述 */
    private final String desc;

    OrderStatusEnum(int code, String desc) {
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
