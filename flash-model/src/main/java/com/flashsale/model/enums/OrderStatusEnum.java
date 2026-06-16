package com.flashsale.model.enums;

public enum OrderStatusEnum {

    PENDING_PAYMENT(0, "pending payment"),
    PAID(1, "paid"),
    CANCELLED(2, "cancelled"),
    REFUNDED(3, "refunded");

    private final int code;
    private final String desc;

    OrderStatusEnum(int code, String desc) {
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
