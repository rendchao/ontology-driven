package com.jjy.infrastructure.exception;

import com.jjy.infrastructure.web.R;

/**
 * 未找到资源（404）
 *
 * @author jjy-base
 */
public class NotFoundException extends BizException {
    public NotFoundException(String message) {
        super(R.CODE_NOT_FOUND, message);
    }
}
