package com.jjy.infrastructure.exception;

import com.jjy.infrastructure.web.R;
import lombok.Getter;

/**
 * M3 规则校验违例（422 规则违反）
 *
 * @author jjy-base
 */
@Getter
public class RuleViolationException extends RuntimeException {

    private final int code;
    private final String ruleId;

    public RuleViolationException(String ruleId, String message) {
        super(message);
        this.code = R.CODE_RULE_VIOLATION;
        this.ruleId = ruleId;
    }
}
