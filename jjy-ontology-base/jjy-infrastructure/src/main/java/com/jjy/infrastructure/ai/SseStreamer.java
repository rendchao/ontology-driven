package com.jjy.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 推送助手：把 AI 对话的中间结果以 OpenAI 兼容事件流形式推给前端。
 *
 * <p>事件类型（前端按 type 分发）：
 * <ul>
 *   <li>delta       — 文本增量（每段 token）</li>
 *   <li>tool_call   — LLM 决定调工具（含 tool 名 + 参数）</li>
 *   <li>tool_result — 工具执行结果（行数 / 摘要 / 错误）</li>
 *   <li>card        — 渲染卡片（数据可视化，跳转提示）</li>
 *   <li>error       — 错误（SQL 校验失败 / 权限不足 / 上游异常）</li>
 *   <li>done        — 流结束（前端关闭连接）</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class SseStreamer {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 发送一个事件
     */
    public void send(SseEmitter emitter, String type, Object data) {
        if (emitter == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("data", data);
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("[SSE] 发送失败: type={}, err={}", type, e.getMessage());
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("[SSE] 发送异常: type={}", type, e);
        }
    }

    public void sendDelta(SseEmitter emitter, String text) {
        send(emitter, "delta", text);
    }

    public void sendToolCall(SseEmitter emitter, String toolName, Map<String, Object> args) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tool", toolName);
        d.put("args", args);
        send(emitter, "tool_call", d);
    }

    public void sendToolResult(SseEmitter emitter, String toolName, Object result) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("tool", toolName);
        d.put("result", result);
        send(emitter, "tool_result", d);
    }

    public void sendCard(SseEmitter emitter, String cardType, Object payload) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("card", cardType);
        d.put("payload", payload);
        send(emitter, "card", d);
    }

    public void sendError(SseEmitter emitter, String code, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", code);
        d.put("message", message);
        send(emitter, "error", d);
    }

    public void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data("{}"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("[SSE] 关闭流异常: {}", e.getMessage());
        }
    }
}
