package com.jjy.application.executor;

/**
 * 规则违反异常
 *
 * @author jjy-base
 */
public class RuleViolationException extends RuntimeException {
    private final String ruleId;
    private final String ruleName;
    private final String reason;

    public RuleViolationException(String ruleId, String ruleName, String reason) {
        super("规则违反 [" + ruleName + "]: " + reason);
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.reason = reason;
    }

    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public String getReason() { return reason; }
}
