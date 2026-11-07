package com.jjy.application.ai;

import com.jjy.infrastructure.ai.AiProperties;
import com.jjy.infrastructure.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 对话审计 Hook：每轮对话记一条 t_audit_log（action='AI_CHAT'）。
 *
 * <p>target_type='AI_CONVERSATION'，target_id=对话 traceId（每次 SSE 一个），payload 写到 before_snapshot 字段。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class AiAuditHook {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiProperties props;

    /**
     * 记录一次 AI 对话
     */
    public void record(LoginUser user, String userMessage, List<Map<String, Object>> fullMessages, long startMillis) {
        if (!props.isAuditEnabled()) return;
        try {
            String traceId = UUID.randomUUID().toString().replace("-", "");
            long elapsed = System.currentTimeMillis() - startMillis;
            StringBuilder sb = new StringBuilder();
            sb.append("user: ").append(userMessage).append("\n---\n");
            for (Map<String, Object> m : fullMessages) {
                String role = String.valueOf(m.get("role"));
                Object content = m.get("content");
                sb.append("[").append(role).append("] ");
                if (content != null) sb.append(content.toString().substring(0, Math.min(2000, content.toString().length())));
                if (m.get("tool_calls") != null) sb.append(" (tool_calls=").append(m.get("tool_calls")).append(")");
                sb.append("\n");
            }
            String payload = sb.toString();
            if (payload.length() > 60000) payload = payload.substring(0, 60000) + "...";
            // 注意占位符与参数必须一一对应：
            //   log_id / actor_id / actor_type / target_id / before_snapshot / ip_address
            //   / user_agent / operation_time / created_by / updated_by = 10 个 ?
            jdbcTemplate.update(
                    "INSERT INTO t_audit_log (log_id, actor_id, actor_type, action, target_type, target_id, " +
                            "before_snapshot, ip_address, user_agent, operation_time, created_by, updated_by, flag) " +
                            "VALUES (?, ?, ?, 'AI_CHAT', 'AI_CONVERSATION', ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    UUID.randomUUID().toString().replace("-", ""),
                    user == null ? "anonymous" : user.getUserId(),
                    user == null ? "ANON" : user.getUserType(),
                    traceId,
                    payload,
                    "AI-Client",
                    "jjy-base/AI",
                    LocalDateTime.now(),
                    user == null ? "system" : user.getUserId(),
                    user == null ? "system" : user.getUserId());
            log.info("[AiAudit] 写入 t_audit_log: traceId={}, elapsed={}ms", traceId, elapsed);
        } catch (Exception e) {
            log.error("[AiAudit] 写入审计失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
