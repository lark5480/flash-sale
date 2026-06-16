package com.flashsale.common.result;

public enum ResultCode {

    // 通用
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    SYSTEM_ERROR(500, "system error"),

    // 秒杀业务
    FLASH_SOLD_OUT(50001, "sold out"),
    FLASH_REPEAT(50002, "repeat purchase not allowed"),
    FLASH_NOT_STARTED(50003, "flash sale not started"),
    FLASH_ENDED(50004, "flash sale ended"),
    STOCK_NOT_ENOUGH(50005, "stock not enough"),
    CAPTCHA_ERROR(50006, "captcha error"),
    RATE_LIMITED(50007, "request too frequent");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
