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

    /** MQ 消息处理结果 Key 前缀，格式：flash:msg:result:{messageKey}，值为 {@link #failedMarker} 等编码后的终态标记 */
    public static final String MSG_RESULT_KEY = "flash:msg:result:";

    /** 处理结果记录过期时间（秒） */
    public static final long MSG_RESULT_TTL = 3600L;

    /** 处理结果：Key 不存在时的等价状态，表示消息尚未被消费或系统异常正在重试 */
    public static final String RESULT_PROCESSING = "PROCESSING";

    /** 处理结果：订单已创建成功（业务终态，重复投递直接跳过） */
    public static final String RESULT_DONE = "DONE";

    /** 处理结果：业务终态失败（库存不足、已达限购等），不再重试 */
    public static final String RESULT_FAILED = "FAILED";

    /** 失败原因分隔符：标记值形如 {@code FAILED:已达每人限购数量}，只按前缀判终态 */
    public static final String RESULT_FAILED_PREFIX = RESULT_FAILED + ":";

    /**
     * 把业务失败原因编进终态标记值。
     * <p>
     * 下单是异步的，客户端只能靠轮询这个标记得知结果；不带上原因就是「失败了，原因你猜」，
     * 而「已售罄」和「已达限购」对用户的下一步动作完全不同（前者等补货，后者根本不用再试）。
     *
     * @param reason 业务异常文案，空白时退化为纯 {@link #RESULT_FAILED}
     * @return 终态标记值
     */
    public static String failedMarker(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return RESULT_FAILED;
        }
        return RESULT_FAILED_PREFIX + reason.trim();
    }

    /**
     * 取标记值的状态部分，供客户端按 DONE / FAILED / PROCESSING 判断。
     *
     * @param marker 结果标记原值，可为 null（未消费或重试中）
     * @return 状态常量，标记缺失时为 {@link #RESULT_PROCESSING}
     */
    public static String statusOf(String marker) {
        if (marker == null) {
            return RESULT_PROCESSING;
        }
        return marker.startsWith(RESULT_FAILED_PREFIX) ? RESULT_FAILED : marker;
    }

    /**
     * 取标记值携带的失败原因。
     *
     * @param marker 结果标记原值，可为 null
     * @return 失败原因；无原因或非失败终态时为 null
     */
    public static String failReasonOf(String marker) {
        if (marker == null || !marker.startsWith(RESULT_FAILED_PREFIX)) {
            return null;
        }
        String reason = marker.substring(RESULT_FAILED_PREFIX.length()).trim();
        return reason.isEmpty() ? null : reason;
    }

    // ==================== 死信队列 ====================

    /** 死信队列主题（RocketMQ 自动路由格式：%DLQ%<consumerGroup>） */
    public static final String FLASH_ORDER_DLT_TOPIC = "%DLQ%" + ORDER_CONSUMER_GROUP;

    /** 死信队列消费者组 */
    public static final String FLASH_ORDER_DLT_CONSUMER_GROUP = "flash-order-dlt-consumer-group";
}
