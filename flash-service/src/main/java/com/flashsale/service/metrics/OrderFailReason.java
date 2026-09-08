package com.flashsale.service.metrics;

/**
 * 秒杀下单失败原因
 * <p>
 * 作为 {@code flashsale.order.fail} 指标的 reason tag 使用，把原本单一的失败计数拆成
 * 可归因的维度，从而区分「正常业务终态（售罄 / 触发限购）」与「需要人工介入的故障
 * （MQ 发送失败 / 系统异常 / 死信）」——混在一个计数器里时，故障会被正常流量淹没。
 * <p>
 * tag 取值固定为本枚举的 code，基数恒定（约 10 个时间序列），不会产生高基数问题。
 */
public enum OrderFailReason {

    /** 秒杀活动不存在或已删除 */
    NOT_FOUND("not_found"),

    /** 活动未激活或尚未到开始时间 */
    NOT_STARTED("not_started"),

    /** 活动已过结束时间 */
    ENDED("ended"),

    /** 库存不足：Redis 预扣失败或 DB 扣减为 0 */
    SOLD_OUT("sold_out"),

    /** 超出每人限购数量或重复购买 */
    REPEAT("repeat"),

    /** 被 Sentinel 限流 / 熔断降级拦截 */
    RATE_LIMITED("rate_limited"),

    /** RocketMQ 发送失败，已回滚 Redis 预扣 */
    MQ_SEND_ERROR("mq_send_error"),

    /** 消费端业务终态失败（库存不足 / 限购兜底），重试无意义，不再重试 */
    BUSINESS_TERMINAL("business_terminal"),

    /** 消费端系统异常，交由 RocketMQ 重试 */
    SYSTEM_ERROR("system_error"),

    /** 重试耗尽进入死信队列，需人工补偿 */
    DEAD_LETTER("dead_letter");

    private final String code;

    OrderFailReason(String code) {
        this.code = code;
    }

    /**
     * 指标 tag 取值
     *
     * @return Prometheus label 值（小写下划线）
     */
    public String getCode() {
        return code;
    }
}
