package com.flashsale.common.enums;

public enum StatusEnum {

    ENABLED(1, "enabled"),
    DISABLED(0, "disabled");

    private final int code;
    private final String desc;

    StatusEnum(int code, String desc) {
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
