package com.flashsale.service.metrics;

/**
 * 秒杀下单消息的消费结果
 * <p>
 * 作为 {@code flashsale.mq.consume} 指标的 result tag 使用，覆盖异步链路的每一种出口，
 * 用于观察消费端健康度：CREATED 是有效产出，DUPLICATE 反映重复投递，
 * BUSINESS_TERMINAL 是正常终态，SYSTEM_RETRY 与 DEAD_LETTER 才是需要告警的故障。
 * <p>
 * tag 取值固定为本枚举的 code，基数恒定，不会产生高基数问题。
 */
public enum ConsumeResult {

    /** 本次投递真正完成扣库存并落库 */
    CREATED("created"),

    /** 重复投递：终态标记已存在或 DB 幂等命中，未重复落库 */
    DUPLICATE("duplicate"),

    /** 业务终态失败：库存不足 / 限购兜底拦截，写 FAILED 不再重试 */
    BUSINESS_TERMINAL("business_terminal"),

    /** 系统异常：不写终态标记，交由 RocketMQ 重试 */
    SYSTEM_RETRY("system_retry"),

    /** 重试耗尽进入死信队列 */
    DEAD_LETTER("dead_letter");

    private final String code;

    ConsumeResult(String code) {
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
