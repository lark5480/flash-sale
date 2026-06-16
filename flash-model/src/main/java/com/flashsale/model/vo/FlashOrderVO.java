package com.flashsale.model.vo;

import com.flashsale.model.entity.FlashOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FlashOrderVO {

    private Long id;
    private Long userId;
    private Long itemId;
    private Long flashSaleId;
    private BigDecimal flashPrice;
    private Integer status;
    private LocalDateTime createTime;
    /** MQ 消息幂等键，Phase 3 异步下单时返回给客户端用于轮询 */
    private String messageKey;

    public static FlashOrderVO from(FlashOrder order) {
        FlashOrderVO vo = new FlashOrderVO();
        vo.setId(order.getId());
        vo.setUserId(order.getUserId());
        vo.setItemId(order.getItemId());
        vo.setFlashSaleId(order.getFlashSaleId());
        vo.setFlashPrice(order.getFlashPrice());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getFlashSaleId() {
        return flashSaleId;
    }

    public void setFlashSaleId(Long flashSaleId) {
        this.flashSaleId = flashSaleId;
    }

    public BigDecimal getFlashPrice() {
        return flashPrice;
    }

    public void setFlashPrice(BigDecimal flashPrice) {
        this.flashPrice = flashPrice;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }
}
