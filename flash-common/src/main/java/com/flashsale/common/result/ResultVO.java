package com.flashsale.common.result;

public class ResultVO<T> {

    private int code;
    private String msg;
    private T data;

    private ResultVO() {
    }

    private ResultVO(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ResultVO<T> success(T data) {
        return new ResultVO<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> ResultVO<T> success() {
        return success(null);
    }

    public static <T> ResultVO<T> fail(ResultCode resultCode) {
        return new ResultVO<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    public static <T> ResultVO<T> fail(ResultCode resultCode, String msg) {
        return new ResultVO<>(resultCode.getCode(), msg, null);
    }

    public static <T> ResultVO<T> fail(int code, String msg) {
        return new ResultVO<>(code, msg, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
