package com.flashsale.model.enums;

public enum FlashSaleStatusEnum {

    PENDING(0, "pending"),
    ACTIVE(1, "active"),
    ENDED(2, "ended"),
    CANCELLED(3, "cancelled");

    private final int code;
    private final String desc;

    FlashSaleStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
