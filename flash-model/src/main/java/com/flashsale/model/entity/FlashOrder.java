package com.flashsale.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.flashsale.common.entity.BaseEntity;

import java.math.BigDecimal;

@TableName("flash_order")
public class FlashOrder extends BaseEntity {

    private Long userId;
    private Long itemId;
    private Long flashSaleId;
    private BigDecimal flashPrice;
    private Integer status;

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
}
