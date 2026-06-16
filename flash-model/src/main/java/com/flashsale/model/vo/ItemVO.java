package com.flashsale.model.vo;

import com.flashsale.model.entity.Item;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ItemVO {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String image;
    private Integer status;
    private LocalDateTime createTime;

    public static ItemVO from(Item item) {
        ItemVO vo = new ItemVO();
        vo.setId(item.getId());
        vo.setName(item.getName());
        vo.setDescription(item.getDescription());
        vo.setPrice(item.getPrice());
        vo.setImage(item.getImage());
        vo.setStatus(item.getStatus());
        vo.setCreateTime(item.getCreateTime());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
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
}
