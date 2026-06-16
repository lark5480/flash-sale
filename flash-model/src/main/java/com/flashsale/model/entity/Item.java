package com.flashsale.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.flashsale.common.entity.BaseEntity;

import java.math.BigDecimal;

@TableName("item")
public class Item extends BaseEntity {

    private String name;
    private String description;
    private BigDecimal price;
    private String image;
    private Integer status;

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
}
