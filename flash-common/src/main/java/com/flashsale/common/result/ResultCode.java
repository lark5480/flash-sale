package com.flashsale.common.result;

public enum ResultCode {

    // 通用
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数有误"),
    UNAUTHORIZED(401, "登录已过期，请重新登录"),
    FORBIDDEN(403, "没有权限执行此操作"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),

    // 秒杀业务
    FLASH_SOLD_OUT(50001, "已售罄"),
    FLASH_REPEAT(50002, "已达到限购数量，请勿重复购买"),
    FLASH_NOT_STARTED(50003, "秒杀尚未开始"),
    FLASH_ENDED(50004, "秒杀已结束"),
    STOCK_NOT_ENOUGH(50005, "库存不足"),
    CAPTCHA_ERROR(50006, "验证码错误或已过期"),
    RATE_LIMITED(50007, "操作太频繁，请稍后再试");

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
