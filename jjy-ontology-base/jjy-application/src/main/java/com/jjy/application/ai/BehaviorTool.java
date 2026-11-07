package com.jjy.application.ai;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 工具：描述一个 M2 行为（输入参数、规则、事件）。
 *
 * <p>不实际执行行为；只告诉 LLM 这个行为需要什么参数、会被哪些规则校验、会产什么事件。
 * 真正的执行仍然走 /behavior/* HTTP 接口或 M6 流程引擎。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class BehaviorTool implements AiTool {

    @Autowired
    private ModelRegistry modelRegistry;

    @Override
    public String name() {
        return "behavior_describe";
    }

    @Override
    public String description() {
        return "描述一个 M2 行为：输入参数、权限要求、规则约束、产生的事件、订阅的事件。用于回答用户关于 \"这个行为需要什么 / 会发生什么\" 的问题。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> behaviorId = new HashMap<>();
        behaviorId.put("type", "string");
        behaviorId.put("description", "M2 行为 ID，如 Product_SaveAsDraft / Order_Approve");

        Map<String, Object> properties = new HashMap<>();
        properties.put("behaviorId", behaviorId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("behaviorId"));
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args, Map<String, Object> userContext) {
        String behaviorId = (String) args.get("behaviorId");
        if (behaviorId == null || behaviorId.isEmpty()) {
            throw new IllegalArgumentException("behaviorId 不能为空");
        }
        Behavior b = modelRegistry.getBehavior(behaviorId);
        if (b == null) {
            Map<String, Object> out = new HashMap<>();
            out.put("behaviorId", behaviorId);
            out.put("exists", false);
            out.put("suggestion", "用 query_lookup target=behavior 列出全部行为，或用相似名称重试");
            return out;
        }
        log.info("[AiTool:behavior] user={} describe={}", SecurityUtils.currentUser().getUsername(), behaviorId);
        Map<String, Object> out = new HashMap<>();
        out.put("behaviorId", b.getId());
        out.put("name", b.getName());
        out.put("ownerEntity", b.getOwnerEntity());
        out.put("behaviorType", b.getBehaviorType());
        out.put("triggerType", b.getTriggerType());
        out.put("requiredPermissions", b.getRequiredPermissions() == null ? new ArrayList<>() : b.getRequiredPermissions());
        out.put("appliedRules", b.getAppliedRules() == null ? new ArrayList<>() : b.getAppliedRules());
        out.put("producedEvents", b.getProducedEvents() == null ? new ArrayList<>() : b.getProducedEvents());
        out.put("subscribedEvents", b.getSubscribedEvents() == null ? new ArrayList<>() : b.getSubscribedEvents());
        out.put("preconditions", b.getPreconditions() == null ? new ArrayList<>() : b.getPreconditions());
        out.put("postconditions", b.getPostconditions() == null ? new ArrayList<>() : b.getPostconditions());
        out.put("idempotencyKey", b.getIdempotencyKey());
        return out;
    }
}
