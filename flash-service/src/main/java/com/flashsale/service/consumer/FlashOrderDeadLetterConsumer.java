package com.flashsale.service.consumer;

import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.service.metrics.FlashSaleMetrics;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单死信队列消费者
 * <p>
 * 消费重试耗尽（maxReconsumeTimes=3）后 RocketMQ 自动路由到 %DLQ% 主题的消息。
 * 此类消息已确认无法正常处理，记录 ERROR 日志用于人工介入或补偿处理。
 * <p>
 * 仅在 flash-api 中启用（flash.flash.consumer.enabled=true），
 * flash-admin 不创建此消费者以避免同组冲突。
 */
@Component
@ConditionalOnProperty(name = "flash.flash.consumer.enabled", havingValue = "true", matchIfMissing = false)
@RocketMQMessageListener(
        topic = RocketMQConstants.FLASH_ORDER_DLT_TOPIC,
        consumerGroup = RocketMQConstants.FLASH_ORDER_DLT_CONSUMER_GROUP
)
public class FlashOrderDeadLetterConsumer implements RocketMQListener<FlashOrderMessage> {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderDeadLetterConsumer.class);

    private final FlashSaleMetrics flashSaleMetrics;

    public FlashOrderDeadLetterConsumer(FlashSaleMetrics flashSaleMetrics) {
        this.flashSaleMetrics = flashSaleMetrics;
    }

    /**
     * 处理死信消息 —— 重试耗尽仍失败，记录 ERROR 日志供人工补偿，并记录失败指标
     */
    @Override
    public void onMessage(FlashOrderMessage message) {
        log.error("[死信队列] 消息重试耗尽仍失败, messageKey={}, userId={}, flashSaleId={}, itemId={}, flashPrice={}",
                message.getMessageKey(), message.getUserId(), message.getFlashSaleId(),
                message.getItemId(), message.getFlashPrice());
        flashSaleMetrics.recordOrderFail();
    }
}
