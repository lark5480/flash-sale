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

    public FlashOrderMessage() {
    }

    public FlashOrderMessage(String messageKey, Long flashSaleId, Long userId, Long itemId, BigDecimal flashPrice) {
        this.messageKey = messageKey;
        this.flashSaleId = flashSaleId;
        this.userId = userId;
        this.itemId = itemId;
        this.flashPrice = flashPrice;
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
}
