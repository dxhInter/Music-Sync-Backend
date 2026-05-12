package com.dxh.spotifysync.common.api;

/**
 * 枚举了一些常用API操作码
 */
public enum ResultCode implements IErrorCode {
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数或业务状态不合法"),
    VALIDATE_FAILED(400, "参数检验失败"),
    FAILED(500, "操作失败"),
    CONFLICT(409, "资源状态冲突"),
    UNAUTHORIZED(401, "暂未登录或token已经过期"),
    FORBIDDEN(403, "没有相关权限");
    private long code;
    private String message;

    private ResultCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
