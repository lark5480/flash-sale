package com.flashsale.service.producer;

import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.service.message.FlashOrderMessage;
import com.flashsale.service.metrics.FlashSaleMetrics;
import io.micrometer.core.instrument.Timer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单消息生产者
 * <p>
 * Redis Lua 预扣成功后，通过 RocketMQ 发送异步下单消息，
 * 实现跨节点分布式削峰。
 * <p>
 * 发送成功与失败分别计入 {@code flashsale.mq.send}（result tag），耗时计入
 * {@code flashsale.mq.send.duration}。发送失败必须向外抛出：
 * 调用方依赖该异常回滚 Redis 预扣，吞掉异常会让库存凭空消失。
 */
@Component
public class FlashOrderProducer {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderProducer.class);

    private final RocketMQTemplate rocketMQTemplate;
    private final FlashSaleMetrics flashSaleMetrics;

    public FlashOrderProducer(RocketMQTemplate rocketMQTemplate, FlashSaleMetrics flashSaleMetrics) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.flashSaleMetrics = flashSaleMetrics;
    }

    /**
     * 发送异步创建订单消息
     *
     * @param message 下单消息体
     */
    public void sendCreateOrderMessage(FlashOrderMessage message) {
        String destination = RocketMQConstants.FLASH_ORDER_TOPIC + ":" + RocketMQConstants.TAG_CREATE;
        Timer.Sample sample = flashSaleMetrics.startTimer();
        try {
            rocketMQTemplate.syncSend(destination, MessageBuilder.withPayload(message).build());
            flashSaleMetrics.recordMqSend(true);
            log.info("[异步下单] RocketMQ 消息已发送, messageKey={}, flashSaleId={}, userId={}",
                    message.getMessageKey(), message.getFlashSaleId(), message.getUserId());
        } catch (Exception e) {
            flashSaleMetrics.recordMqSend(false);
            log.error("[异步下单] RocketMQ 消息发送失败, messageKey={}, flashSaleId={}",
                    message.getMessageKey(), message.getFlashSaleId(), e);
            throw e;
        } finally {
            flashSaleMetrics.stopMqSendTimer(sample);
        }
    }
}
