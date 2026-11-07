package com.jjy.infrastructure.health;

import com.jjy.infrastructure.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务级健康检查端点：/api/v1/health
 *
 * 聚合以下 HealthIndicator：
 *   - db          （Spring Boot DataSourceHealthIndicator，自动启用）
 *   - diskSpace   （Spring Boot DiskSpaceHealthIndicator）
 *   - ping        （Spring Boot PingHealthIndicator）
 *   - outbox      （OutboxHealthIndicator，自定义）
 *   - deadLetter  （DeadLetterHealthIndicator，自定义）
 *   - scenario    （ScenarioHealthIndicator，自定义）
 *
 * 返回示例：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "UP",
 *   "data": {
 *     "status": "UP",
 *     "timestamp": "2026-10-01T12:00:00Z",
 *     "components": {
 *       "db":        { "status": "UP", "details": { "database": "MySQL", "validationQuery": "isValid()" } },
 *       "diskSpace": { "status": "UP", "details": { "total": ..., "free": ... } },
 *       "outbox":    { "status": "UP", "details": { "pending": 12, "retry": 0, "dead": 0 } },
 *       "deadLetter":{ "status": "UP", "details": { "unresolved": 0 } },
 *       "scenario":  { "status": "UP", "details": { "running_or_waiting": 3, "completed": 17, "failed_or_terminated": 0 } }
 *     }
 *   }
 * }
 * </pre>
 *
 * HTTP 状态码：UP 返回 200，DOWN/OUT_OF_SERVICE 返回 503，方便负载均衡器 / Docker HEALTHCHECK 识别。
 *
 * @author jjy-base
 */
@Slf4j
@RestController("businessHealthController")
@RequestMapping("/health")
public class HealthController {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @GetMapping
    public R<Map<String, Object>> health() {
        HealthComponent component = healthEndpoint.health();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", component.getStatus().getCode());
        body.put("timestamp", Instant.now().toString());
        if (component instanceof Health) {
            Map<String, Object> details = ((Health) component).getDetails();
            if (details != null && !details.isEmpty()) {
                body.put("details", details);
            }
        }

        Map<String, Object> components = new LinkedHashMap<>();
        if (component instanceof org.springframework.boot.actuate.health.CompositeHealth) {
            org.springframework.boot.actuate.health.CompositeHealth composite =
                    (org.springframework.boot.actuate.health.CompositeHealth) component;
            composite.getComponents().forEach((name, sub) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("status", sub.getStatus().getCode());
                if (sub instanceof Health) {
                    Map<String, Object> details = ((Health) sub).getDetails();
                    if (details != null && !details.isEmpty()) {
                        item.put("details", details);
                    }
                }
                components.put(name, item);
            });
        }
        body.put("components", components);

        if (Status.UP.equals(component.getStatus())) {
            return R.ok(body, "UP");
        } else {
            return R.fail(503, component.getStatus().getCode(), body);
        }
    }

    /**
     * 轻量级 ping，用于 Liveness Probe（k8s liveness）。
     * 不查数据库，只确认应用进程存活。
     */
    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("pong");
    }

    /**
     * 就绪检查（k8s readiness），仅 DB UP 才返回 200。
     */
    @GetMapping("/ready")
    public R<Map<String, Object>> ready() {
        HealthComponent component = healthEndpoint.health();
        boolean dbUp = false;
        if (component instanceof org.springframework.boot.actuate.health.CompositeHealth) {
            org.springframework.boot.actuate.health.CompositeHealth composite =
                    (org.springframework.boot.actuate.health.CompositeHealth) component;
            Health db = (Health) composite.getComponents().get("db");
            dbUp = db != null && Status.UP.equals(db.getStatus());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", dbUp);
        body.put("timestamp", Instant.now().toString());
        return dbUp ? R.ok(body, "READY") : R.fail(503, "NOT_READY", body);
    }
}
