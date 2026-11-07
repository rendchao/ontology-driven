package com.jjy.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 事件发件箱健康检查。
 *
 * 阈值（可在 application.yml 用 jjy.outbox.* 覆盖）：
 *   - pending  > 1000  → DOWN（消息积压严重，调度器可能没跑）
 *   - retry    > 500   → DOWN（重试堆积）
 *   - dead     > 0     → UP with warning（死信需人工介入，参考 /actuator/deadletter）
 *
 * @author jjy-base
 */
@Slf4j
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private static final int PENDING_THRESHOLD = 1000;
    private static final int RETRY_THRESHOLD   = 500;

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public Health health() {
        try {
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_outbox WHERE status = 0", Integer.class);
            Integer retry = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_outbox WHERE status = 2", Integer.class);
            Integer dead = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event_outbox WHERE status = 3", Integer.class);
            int p = pending == null ? 0 : pending;
            int r = retry == null ? 0 : retry;
            int d = dead == null ? 0 : dead;

            Health.Builder b;
            if (p > PENDING_THRESHOLD) {
                b = Health.down().withDetail("reason", "pending 超过阈值 " + PENDING_THRESHOLD);
            } else if (r > RETRY_THRESHOLD) {
                b = Health.down().withDetail("reason", "retry 超过阈值 " + RETRY_THRESHOLD);
            } else {
                b = Health.up();
            }

            return b.withDetail("pending", p)
                    .withDetail("retry", r)
                    .withDetail("dead", d)
                    .withDetail("pending_threshold", PENDING_THRESHOLD)
                    .withDetail("retry_threshold", RETRY_THRESHOLD)
                    .build();
        } catch (Exception e) {
            log.warn("OutboxHealthIndicator 查询失败", e);
            return Health.down().withException(e).build();
        }
    }
}
