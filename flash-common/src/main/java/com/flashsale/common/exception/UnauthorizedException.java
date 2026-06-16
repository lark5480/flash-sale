package com.flashsale.common.exception;

import com.flashsale.common.result.ResultCode;

public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super(ResultCode.UNAUTHORIZED.getMsg());
    }

    public UnauthorizedException(String msg) {
        super(msg);
    }
}
