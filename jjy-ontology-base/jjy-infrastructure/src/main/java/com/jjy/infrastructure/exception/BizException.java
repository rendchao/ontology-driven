package com.jjy.infrastructure.exception;

import com.jjy.infrastructure.web.R;
import lombok.Getter;

/**
 * 业务异常（422 业务错误 / 409 冲突 / 404 未找到 等）
 *
 * @author jjy-base
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = R.CODE_BIZ_ERROR;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** (String, Throwable) 便捷构造，默认业务错误码 5000 */
    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = R.CODE_BIZ_ERROR;
    }
}
