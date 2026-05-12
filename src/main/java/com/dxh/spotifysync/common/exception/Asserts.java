package com.dxh.spotifysync.common.exception;


import com.dxh.spotifysync.common.api.IErrorCode;

/**
 * 断言处理类，用于抛出各种API异常
 */
public class Asserts {
    public static void fail(String message) {
        throw new ApiException(message);
    }

    public static void fail(IErrorCode errorCode) {
        throw new ApiException(errorCode);
    }

    public static void fail(IErrorCode errorCode, String message) {
        throw new ApiException(errorCode, message);
    }
}
