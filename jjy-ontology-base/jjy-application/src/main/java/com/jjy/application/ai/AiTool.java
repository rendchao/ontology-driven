package com.jjy.application.ai;

import java.util.Map;

/**
 * AI 工具接口：每个工具对应 LLM 可调用的一个 function。
 *
 * <p>工具实现 = 一个 Java 方法，把 LLM 给的参数（Map）转成系统操作，返回结果（Map）也是
 * JSON-friendly 的；错误用 IllegalArgumentException 抛出，AiChatService 会转成 error 事件给 LLM 自我修正。</p>
 *
 * @author jjy-base
 */
public interface AiTool {

    /** 工具名（OpenAI function name 字段） */
    String name();

    /** 工具中文说明（给 LLM 看） */
    String description();

    /** OpenAI function calling 的 parameters JSON Schema（Map 形态） */
    Map<String, Object> parametersSchema();

    /**
     * 执行工具
     *
     * @param args   LLM 给的参数（与 parametersSchema 对应）
     * @param userContext 调用方用户上下文（userId/customerId/permissions），工具可决定是否允许
     * @return 工具结果（Map，序列化为 JSON 后回给 LLM）
     */
    Object execute(Map<String, Object> args, Map<String, Object> userContext);
}
