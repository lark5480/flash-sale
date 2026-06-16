package com.flashsale.common.exception;

import com.flashsale.common.result.ResultCode;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super(ResultCode.FORBIDDEN.getMsg());
    }

    public ForbiddenException(String msg) {
        super(msg);
    }
}
