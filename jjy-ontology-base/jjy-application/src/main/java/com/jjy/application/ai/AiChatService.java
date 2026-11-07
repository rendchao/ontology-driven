package com.jjy.application.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.infrastructure.ai.AiProperties;
import com.jjy.infrastructure.ai.LlmClient;
import com.jjy.infrastructure.ai.SseStreamer;
import com.jjy.infrastructure.security.LoginUser;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话核心服务：编排 system prompt + 工具调用 + 流式推送。
 *
 * <p>单次对话的循环：
 * <pre>
 *   ┌─ LLM 流式响应 → 累积 content / tool_calls
 *   ├─ 若 finish_reason=tool_calls：
 *   │    ├─ 把 assistant message（含 tool_calls）push 到 messages
 *   │    ├─ 调每个 tool → 拿到 tool result message
 *   │    └─ 继续下一轮
 *   ├─ 若 finish_reason=stop：
 *   │    └─ 把完整 assistant 文本 push 到 messages，流结束
 *   └─ 超过 maxToolRounds → 强制结束（防 LLM 死循环）
 * </pre>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class AiChatService {

    @Autowired
    private AiProperties props;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private SseStreamer sse;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SystemPromptBuilder promptBuilder;

    @Autowired
    private AiAuditHook auditHook;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 处理单次对话（同步阻塞，AI 服务跑完后才返回）
     *
     * @param userMessage 用户的最新输入
     * @param history     历史对话（不含 system）
     * @param emitter     SSE 推流通道
     */
    public void chat(String userMessage, List<Map<String, Object>> history, SseEmitter emitter) {
        if (!props.isEnabled()) {
            sse.sendError(emitter, "AI_DISABLED", "AI 未启用（jjy.ai.enabled=false）");
            sse.sendDone(emitter);
            return;
        }
        LoginUser user = SecurityUtils.currentUser();
        long t0 = System.currentTimeMillis();

        // 1) 构造 messages
        Map<String, Object> userCtx = buildUserContext(user);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", promptBuilder.build(userCtx));
        messages.add(sysMsg);
        if (history != null) messages.addAll(history);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        // 2) 工具定义
        List<Map<String, Object>> tools = toolRegistry.toOpenAiTools();
        log.info("[AiChat] user={} start, tools={}, historyLen={}", user.getUsername(), tools.size(),
                history == null ? 0 : history.size());

        // 3) 多轮 tool-call 循环
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            for (int round = 0; round < props.getMaxToolRounds() && !finished.get(); round++) {
                runOneRound(messages, tools, emitter, finished, user, t0, round);
            }
            if (!finished.get()) {
                sse.sendError(emitter, "MAX_ROUNDS", "工具调用超过 " + props.getMaxToolRounds() + " 轮，已强制结束");
            }
        } catch (Exception e) {
            log.error("[AiChat] 异常: {}", e.getMessage(), e);
            sse.sendError(emitter, "EXCEPTION", e.getMessage());
        } finally {
            sse.sendDone(emitter);
            auditHook.record(user, userMessage, messages, t0);
        }
    }

    /**
     * 单轮 LLM 调用 + 工具执行
     */
    private void runOneRound(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                             SseEmitter emitter, AtomicBoolean finished, LoginUser user, long t0, int round) {
        StringBuilder accumulated = new StringBuilder();
        Map<Integer, Map<String, Object>> toolCallsAccumulator = new HashMap<>();

        llmClient.streamChat(messages, tools, new LlmClient.LineEmitter() {
            @Override
            public void onLine(String dataLine) {
                // 1) 文本增量
                String delta = llmClient.extractDeltaContent(dataLine);
                if (delta != null) {
                    accumulated.append(delta);
                    sse.sendDelta(emitter, delta);
                }
                // 2) 工具调用增量（OpenAI 流式 tool_calls 累积式：每个 chunk 可能含 index/function.name 等片段）
                List<Map<String, Object>> streamed = llmClient.extractStreamedToolCalls(dataLine);
                if (streamed != null) {
                    for (Map<String, Object> tc : streamed) {
                        Object idxObj = tc.get("index");
                        int idx = idxObj instanceof Number ? ((Number) idxObj).intValue() : 0;
                        toolCallsAccumulator.computeIfAbsent(idx, k -> new HashMap<>());
                        Map<String, Object> acc = toolCallsAccumulator.get(idx);
                        if (tc.get("id") != null) acc.put("id", tc.get("id"));
                        if (tc.get("type") != null) acc.put("type", tc.get("type"));
                        Object fn = tc.get("function");
                        if (fn instanceof Map) {
                            Map<String, Object> fnMap = (Map<String, Object>) fn;
                            Object name = fnMap.get("name");
                            Object args = fnMap.get("arguments");
                            acc.computeIfAbsent("function", k -> new HashMap<String, Object>());
                            Map<String, Object> accFn = (Map<String, Object>) acc.get("function");
                            if (name != null) accFn.put("name", name.toString());
                            if (args != null) {
                                accFn.put("arguments", accFn.getOrDefault("arguments", "") + args.toString());
                            }
                        }
                    }
                }
            }
            @Override
            public void onComplete() {
                // 流结束：看有没有 tool_calls
                if (!toolCallsAccumulator.isEmpty()) {
                    handleToolCalls(messages, toolCallsAccumulator, emitter, user);
                } else {
                    // 把累积文本作为 assistant 消息
                    Map<String, Object> assistantMsg = new HashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", accumulated.toString());
                    messages.add(assistantMsg);
                    finished.set(true);
                    log.info("[AiChat] round={} stop, totalLen={}, elapsed={}ms",
                            round, accumulated.length(), System.currentTimeMillis() - t0);
                }
            }
            @Override
            public void onError(Throwable t) {
                sse.sendError(emitter, "LLM_ERROR", "上游 LLM 异常: " + t.getMessage());
                finished.set(true);
            }
        });
    }

    /**
     * 处理 tool_calls：执行工具 → 把结果 push 到 messages
     */
    private void handleToolCalls(List<Map<String, Object>> messages, Map<Integer, Map<String, Object>> toolCalls,
                                 SseEmitter emitter, LoginUser user) {
        // 1) assistant tool_calls message
        List<Map<String, Object>> tcs = new ArrayList<>(toolCalls.values());
        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", null);
        assistantMsg.put("tool_calls", tcs);
        messages.add(assistantMsg);

        // 2) 每个 tool_call 一个 tool result
        for (Map<String, Object> tc : tcs) {
            String toolCallId = (String) tc.get("id");
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            String toolName = (String) fn.get("name");
            String argsJson = (String) fn.get("arguments");
            Map<String, Object> argsMap = parseArgs(argsJson);
            sse.sendToolCall(emitter, toolName, argsMap);

            AiTool tool = toolRegistry.get(toolName);
            Object result;
            try {
                if (tool == null) {
                    result = errorMap("TOOL_NOT_FOUND", "工具未注册: " + toolName);
                } else {
                    Map<String, Object> userCtx = buildUserContext(user);
                    result = tool.execute(argsMap, userCtx);
                }
            } catch (IllegalArgumentException e) {
                log.warn("[AiChat] 工具执行失败: tool={}, err={}", toolName, e.getMessage());
                result = errorMap("INVALID_ARGS", e.getMessage());
            } catch (Exception e) {
                log.error("[AiChat] 工具异常: tool={}", toolName, e);
                result = errorMap("TOOL_ERROR", e.getMessage());
            }
            sse.sendToolResult(emitter, toolName, result);

            // 如果是 navigation / sql_readonly 工具结果含 cardType，再发 card 事件（前端展示）
            if (result instanceof Map) {
                Object cardType = ((Map<?, ?>) result).get("cardType");
                if (cardType != null) {
                    sse.sendCard(emitter, cardType.toString(), result);
                }
            }

            Map<String, Object> toolMsg = new HashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", toolCallId);
            toolMsg.put("content", toJsonSafe(result));
            messages.add(toolMsg);
        }
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(argsJson, Map.class);
        } catch (Exception e) {
            log.warn("[AiChat] 解析工具参数失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJsonSafe(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private Map<String, Object> errorMap(String code, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", code);
        m.put("message", msg);
        return m;
    }

    private Map<String, Object> buildUserContext(LoginUser user) {
        Map<String, Object> ctx = new HashMap<>();
        if (user == null) return ctx;
        ctx.put("userId", user.getUserId());
        ctx.put("username", user.getUsername());
        ctx.put("userType", user.getUserType());
        ctx.put("customerId", user.getCustomerId());
        return ctx;
    }
}
