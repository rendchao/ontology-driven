package com.jjy.infrastructure.exception;

import com.jjy.infrastructure.web.R;

/**
 * 资源冲突（409）：唯一键冲突、状态机非法流转等
 *
 * @author jjy-base
 */
public class ConflictException extends BizException {
    public ConflictException(String message) {
        super(R.CODE_CONFLICT, message);
    }
}
