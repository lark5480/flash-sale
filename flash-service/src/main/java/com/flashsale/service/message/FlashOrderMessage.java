package com.flashsale.service.message;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * RocketMQ 秒杀下单消息体
 */
public class FlashOrderMessage implements Serializable {

    /** 消息唯一键：flashSaleId_userId_timestamp，用于幂等校验 */
    private String messageKey;

    private Long flashSaleId;
    private Long userId;
    private Long itemId;
    private BigDecimal flashPrice;

    /**
     * 消息生产时刻（毫秒时间戳），用于统计「下单 → 落库」的端到端延迟。
     * 存量消息没有该字段时反序列化为 null，消费端会跳过延迟统计。
     */
    private Long produceTime;

    public FlashOrderMessage() {
    }

    public FlashOrderMessage(String messageKey, Long flashSaleId, Long userId, Long itemId, BigDecimal flashPrice) {
        this(messageKey, flashSaleId, userId, itemId, flashPrice, System.currentTimeMillis());
    }

    public FlashOrderMessage(String messageKey, Long flashSaleId, Long userId, Long itemId,
                             BigDecimal flashPrice, Long produceTime) {
        this.messageKey = messageKey;
        this.flashSaleId = flashSaleId;
        this.userId = userId;
        this.itemId = itemId;
        this.flashPrice = flashPrice;
        this.produceTime = produceTime;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public Long getFlashSaleId() {
        return flashSaleId;
    }

    public void setFlashSaleId(Long flashSaleId) {
        this.flashSaleId = flashSaleId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public BigDecimal getFlashPrice() {
        return flashPrice;
    }

    public void setFlashPrice(BigDecimal flashPrice) {
        this.flashPrice = flashPrice;
    }

    public Long getProduceTime() {
        return produceTime;
    }

    public void setProduceTime(Long produceTime) {
        this.produceTime = produceTime;
    }
}
