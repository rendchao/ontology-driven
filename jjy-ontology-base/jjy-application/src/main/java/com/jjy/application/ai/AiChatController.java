package com.jjy.application.ai;

import com.jjy.infrastructure.ai.AiProperties;
import com.jjy.infrastructure.ai.SseStreamer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * AI 对话 HTTP 端点。
 *
 * <p>仅在 jjy.ai.enabled=true 时注册（@ConditionalOnProperty）。</p>
 *
 * <p>POST /ai/chat → SSE 推送，事件类型见 {@link SseStreamer}。</p>
 *
 * <p>放在 jjy-application 是因为它依赖 application 层的 AiChatService（基础设施层不应反向依赖 application）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@ConditionalOnProperty(prefix = "jjy.ai", name = "enabled", havingValue = "true")
public class AiChatController {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiProperties props;

    /**
     * 流式对话入口。
     *
     * <pre>
     * Request:
     * {
     *   "message": "查询当前库存中所有的 SKU",
     *   "history": [ { "role": "user", "content": "..." }, { "role": "assistant", "content": "..." } ]
     * }
     * </pre>
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, Object> body) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时
        if (!props.isEnabled()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"type\":\"error\",\"data\":{\"code\":\"AI_DISABLED\",\"message\":\"AI 未启用\"}}",
                                MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }
        String message = (String) body.get("message");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");
        if (message == null || message.trim().isEmpty()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"type\":\"error\",\"data\":{\"code\":\"EMPTY_MESSAGE\",\"message\":\"message 不能为空\"}}",
                                MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }
        // 后台线程跑 chat（不阻塞 controller 线程）
        new Thread(() -> {
            try {
                aiChatService.chat(message, history, emitter);
            } catch (Exception e) {
                log.error("[AiChat] controller 异常: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"type\":\"error\",\"data\":{\"code\":\"INTERNAL\",\"message\":\"" +
                                    e.getMessage().replace("\"", "'") + "\"}}", MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException ignore) {}
            }
        }, "ai-chat-" + System.currentTimeMillis()).start();
        return emitter;
    }
}
