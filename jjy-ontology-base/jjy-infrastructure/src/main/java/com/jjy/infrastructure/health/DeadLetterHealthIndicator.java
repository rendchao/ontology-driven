package com.jjy.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 死信健康检查。
 *
 * 阈值：
 *   - unresolved > 100  → DOWN（死信堆积，业务方需人工处理）
 *   - unresolved <= 100 → UP（正常运维范围）
 *
 * @author jjy-base
 */
@Slf4j
@Component("deadletter")
public class DeadLetterHealthIndicator implements HealthIndicator {

    private static final int UNRESOLVED_THRESHOLD = 100;

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public Health health() {
        try {
            Integer unresolved = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_dead_letter WHERE resolved_flag = 0", Integer.class);
            int u = unresolved == null ? 0 : unresolved;

            Health.Builder b = u > UNRESOLVED_THRESHOLD
                    ? Health.down().withDetail("reason", "未处理死信超过阈值 " + UNRESOLVED_THRESHOLD)
                    : Health.up();

            return b.withDetail("unresolved", u)
                    .withDetail("unresolved_threshold", UNRESOLVED_THRESHOLD)
                    .build();
        } catch (Exception e) {
            log.warn("DeadLetterHealthIndicator 查询失败", e);
            return Health.down().withException(e).build();
        }
    }
}
