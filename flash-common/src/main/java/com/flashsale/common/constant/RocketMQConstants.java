package com.flashsale.common.constant;

/**
 * RocketMQ 常量 —— 秒杀异步下单
 *
 * @author flash-sale
 */
public final class RocketMQConstants {

    private RocketMQConstants() {
    }

    // ==================== Topic & Tag ====================

    /** 秒杀订单 Topic */
    public static final String FLASH_ORDER_TOPIC = "flash-order-topic";

    /** 下单 Tag */
    public static final String TAG_CREATE = "create";

    // ==================== Consumer Group ====================

    /** 订单消费者组 */
    public static final String ORDER_CONSUMER_GROUP = "flash-order-consumer-group";

    // ==================== 幂等 Redis Key ====================

    /** MQ 消息幂等 Key 前缀，格式：flash:msg:processed:{messageKey} */
    public static final String MSG_PROCESSED_KEY = "flash:msg:processed:";

    /** 幂等记录过期时间（秒） */
    public static final long MSG_PROCESSED_TTL = 3600L;
}
