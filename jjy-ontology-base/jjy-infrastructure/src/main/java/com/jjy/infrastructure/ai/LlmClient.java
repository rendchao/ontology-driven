package com.jjy.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 通用 LLM 客户端：OpenAI /v1/chat/completions 协议。
 *
 * <p>基于 JDK 内置 HttpURLConnection（Java 8 兼容，不需要 webflux/jdk11+）。
 * 流式响应按 SSE 逐行解析。</p>
 *
 * <p>支持：
 * <ul>
 *   <li>流式（SSE，LineEmitter 回调）— AI Chat 默认走这个</li>
 *   <li>非流式（一次性返回 JsonNode）— 单步 tool-call 完成后的总结</li>
 *   <li>工具调用（tools/tool_choice）— 全部走 JSON 协议</li>
 * </ul>
 *
 * <p>兼容：OpenAI / Azure OpenAI / DeepSeek / Moonshot Kimi / 阿里通义千问（OpenAI 兼容模式）/ Ollama（OpenAI 兼容模式）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class LlmClient {

    @Autowired
    private AiProperties props;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 流式对话回调接口
     */
    public interface LineEmitter {
        /** 收到一行 data: 内容（去掉前缀 "data: "） */
        void onLine(String dataLine);
        /** 流结束（收到 [DONE] 或服务端关闭） */
        void onComplete();
        /** 异常 */
        void onError(Throwable t);
    }

    /**
     * 流式对话（SSE）。
     *
     * <p>实现说明：HttpURLConnection 本身没有 async API；这里用独立线程读取响应流，
     * 业务线程把每行 data 回调到 emitter，避免阻塞 LLM 上游慢响应。</p>
     */
    public void streamChat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, LineEmitter emitter) {
        if (!props.isEnabled()) {
            emitter.onError(new IllegalStateException("AI 未启用（jjy.ai.enabled=false）"));
            return;
        }
        new Thread(() -> doStream(messages, tools, emitter), "llm-stream-" + System.currentTimeMillis()).start();
    }

    private void doStream(List<Map<String, Object>> messages, List<Map<String, Object>> tools, LineEmitter emitter) {
        HttpURLConnection conn = null;
        try {
            String body = buildRequestBody(messages, tools, true);
            URL url = new URL(props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(props.getTimeoutSeconds() * 1000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (props.getApiKey() != null && !props.getApiKey().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + props.getApiKey());
            }
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                try (InputStream es = conn.getErrorStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                    StringBuilder errBody = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) errBody.append(l);
                    emitter.onError(new RuntimeException("LLM 上游 HTTP " + code + ": " + errBody));
                }
                return;
            }
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                boolean done = false;
                while ((line = br.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (line.startsWith(":")) continue;
                    if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) { done = true; break; }
                        emitter.onLine(payload);
                    }
                }
                if (!done) {
                    log.warn("[LLM] 流未以 [DONE] 结束（可能服务端异常关闭）");
                }
                emitter.onComplete();
            }
        } catch (Exception e) {
            emitter.onError(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 非流式对话（一次性返回）。用 RestTemplate（Spring 已自带）走 blocking HTTP。
     */
    public JsonNode chatOnce(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!props.isEnabled()) {
            throw new IllegalStateException("AI 未启用（jjy.ai.enabled=false）");
        }
        try {
            String body = buildRequestBody(messages, tools, false);
            RestTemplate rt = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (props.getApiKey() != null && !props.getApiKey().isEmpty()) {
                headers.set("Authorization", "Bearer " + props.getApiKey());
            }
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = rt.exchange(
                    props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions",
                    HttpMethod.POST, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.error("[LLM] 非流式调用失败: HTTP {} - {}", resp.getStatusCode(), resp.getBody());
                throw new RuntimeException("LLM 上游 HTTP " + resp.getStatusCode());
            }
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            log.error("[LLM] 非流式调用异常: {}", e.getMessage(), e);
            throw new RuntimeException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean stream) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", stream);
        body.set("messages", objectMapper.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", objectMapper.valueToTree(tools));
            body.put("tool_choice", "auto");
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 从 OpenAI 完整响应中提取第一个 choice 的 content
     */
    public String extractContent(JsonNode response) {
        if (response == null) return null;
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.size() == 0) return null;
        JsonNode first = choices.get(0);
        return first.path("message").path("content").asText(null);
    }

    /**
     * 从完整响应中提取 tool_calls
     */
    public List<Map<String, Object>> extractToolCalls(JsonNode response) {
        if (response == null) return null;
        JsonNode message = response.path("choices").path(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.size() == 0) return null;
        try {
            return objectMapper.convertValue(toolCalls,
                    objectMapper.getTypeFactory().constructCollectionType(java.util.ArrayList.class, Map.class));
        } catch (Exception e) {
            log.error("[LLM] 解析 tool_calls 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从流式 SSE 行中提取 delta.content（增量文本）
     */
    public String extractDeltaContent(String sseDataLine) {
        if (sseDataLine == null || sseDataLine.isEmpty()) return null;
        try {
            JsonNode root = objectMapper.readTree(sseDataLine);
            JsonNode delta = root.path("choices").path(0).path("delta");
            if (delta.has("content")) {
                JsonNode c = delta.get("content");
                if (c.isNull()) return null;
                return c.asText();
            }
        } catch (Exception ignore) {}
        return null;
    }

    /**
     * 从流式 SSE 行中提取累积的 tool_calls
     */
    public List<Map<String, Object>> extractStreamedToolCalls(String sseDataLine) {
        if (sseDataLine == null) return null;
        try {
            JsonNode root = objectMapper.readTree(sseDataLine);
            JsonNode toolCalls = root.path("choices").path(0).path("delta").path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.size() == 0) return null;
            return objectMapper.convertValue(toolCalls,
                    objectMapper.getTypeFactory().constructCollectionType(java.util.ArrayList.class, Map.class));
        } catch (Exception e) {
            return null;
        }
    }
}
