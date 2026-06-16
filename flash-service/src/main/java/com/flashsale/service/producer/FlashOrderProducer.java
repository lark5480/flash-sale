package com.flashsale.service.producer;

import com.flashsale.common.constant.RocketMQConstants;
import com.flashsale.service.message.FlashOrderMessage;
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
 */
@Component
public class FlashOrderProducer {

    private static final Logger log = LoggerFactory.getLogger(FlashOrderProducer.class);

    private final RocketMQTemplate rocketMQTemplate;

    public FlashOrderProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 发送异步创建订单消息
     *
     * @param message 下单消息体
     */
    public void sendCreateOrderMessage(FlashOrderMessage message) {
        String destination = RocketMQConstants.FLASH_ORDER_TOPIC + ":" + RocketMQConstants.TAG_CREATE;
        rocketMQTemplate.syncSend(destination, MessageBuilder.withPayload(message).build());
        log.info("[异步下单] RocketMQ 消息已发送, messageKey={}, flashSaleId={}, userId={}",
                message.getMessageKey(), message.getFlashSaleId(), message.getUserId());
    }
}
