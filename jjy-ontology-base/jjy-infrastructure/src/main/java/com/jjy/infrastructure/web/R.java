package com.jjy.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应格式
 * <pre>
 * { "code": 2000, "message": "OK", "data": {...}, "traceId": "abc" }
 * </pre>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CODE_SUCCESS = 2000;
    public static final int CODE_BAD_REQUEST = 4000;
    public static final int CODE_UNAUTHORIZED = 4001;
    public static final int CODE_FORBIDDEN = 4003;
    public static final int CODE_NOT_FOUND = 4004;
    public static final int CODE_CONFLICT = 4009;
    public static final int CODE_RULE_VIOLATION = 4221;
    public static final int CODE_BIZ_ERROR = 4220;
    public static final int CODE_INTERNAL = 5000;

    private int code;
    private String message;
    private T data;
    private String traceId;

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(CODE_SUCCESS, "OK", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CODE_SUCCESS, "OK", data);
    }

    public static <T> R<T> ok(T data, String message) {
        return new R<>(CODE_SUCCESS, message, data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public static <T> R<T> fail(int code, String message, T data) {
        return new R<>(code, message, data);
    }

    public boolean isSuccess() {
        return code == CODE_SUCCESS;
    }
}
