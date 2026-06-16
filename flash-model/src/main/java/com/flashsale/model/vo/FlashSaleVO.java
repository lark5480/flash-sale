package com.flashsale.model.vo;

import com.flashsale.model.entity.FlashSale;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FlashSaleVO {

    private Long id;
    private Long itemId;
    private String itemName;
    private String itemImage;
    private BigDecimal flashPrice;
    private BigDecimal originalPrice;
    private Integer stock;
    private Integer limitPerUser;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;

    public static FlashSaleVO from(FlashSale flashSale) {
        FlashSaleVO vo = new FlashSaleVO();
        vo.setId(flashSale.getId());
        vo.setItemId(flashSale.getItemId());
        vo.setFlashPrice(flashSale.getFlashPrice());
        vo.setStock(flashSale.getStock());
        vo.setLimitPerUser(flashSale.getLimitPerUser());
        vo.setStartTime(flashSale.getStartTime());
        vo.setEndTime(flashSale.getEndTime());
        vo.setStatus(flashSale.getStatus());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public String getItemImage() {
        return itemImage;
    }

    public void setItemImage(String itemImage) {
        this.itemImage = itemImage;
    }

    public BigDecimal getFlashPrice() {
        return flashPrice;
    }

    public void setFlashPrice(BigDecimal flashPrice) {
        this.flashPrice = flashPrice;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
        this.originalPrice = originalPrice;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Integer getLimitPerUser() {
        return limitPerUser;
    }

    public void setLimitPerUser(Integer limitPerUser) {
        this.limitPerUser = limitPerUser;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
