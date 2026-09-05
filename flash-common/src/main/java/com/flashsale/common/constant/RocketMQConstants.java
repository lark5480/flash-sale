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

    // ==================== 消息处理结果 Redis Key ====================

    /** MQ 消息处理结果 Key 前缀，格式：flash:msg:result:{messageKey}，值为下方三个状态常量之一 */
    public static final String MSG_RESULT_KEY = "flash:msg:result:";

    /** 处理结果记录过期时间（秒） */
    public static final long MSG_RESULT_TTL = 3600L;

    /** 处理结果：Key 不存在时的等价状态，表示消息尚未被消费或系统异常正在重试 */
    public static final String RESULT_PROCESSING = "PROCESSING";

    /** 处理结果：订单已创建成功（业务终态，重复投递直接跳过） */
    public static final String RESULT_DONE = "DONE";

    /** 处理结果：业务终态失败（库存不足、活动已结束等），不再重试 */
    public static final String RESULT_FAILED = "FAILED";

    // ==================== 死信队列 ====================

    /** 死信队列主题（RocketMQ 自动路由格式：%DLQ%<consumerGroup>） */
    public static final String FLASH_ORDER_DLT_TOPIC = "%DLQ%" + ORDER_CONSUMER_GROUP;

    /** 死信队列消费者组 */
    public static final String FLASH_ORDER_DLT_CONSUMER_GROUP = "flash-order-dlt-consumer-group";
}
