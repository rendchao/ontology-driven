package com.jjy.application.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 工具注册表：聚合所有 {@link AiTool} 实现，构造 LLM 的 tools 参数。
 *
 * <p>LLM 通过 function calling 选中工具后，AiChatService 用 name 查到此处的实现并执行。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    @Autowired
    public ToolRegistry(List<AiTool> aiTools) {
        for (AiTool t : aiTools) {
            tools.put(t.name(), t);
            log.info("[ToolRegistry] 注册 AI 工具: {} - {}", t.name(), t.description());
        }
    }

    public AiTool get(String name) {
        return tools.get(name);
    }

    /**
     * 构造 OpenAI 协议的 tools 数组
     */
    public List<Map<String, Object>> toOpenAiTools() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiTool t : tools.values()) {
            Map<String, Object> def = new HashMap<>();
            def.put("type", "function");
            Map<String, Object> fn = new HashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.parametersSchema());
            def.put("function", fn);
            list.add(def);
        }
        return list;
    }

    public int size() {
        return tools.size();
    }
}
