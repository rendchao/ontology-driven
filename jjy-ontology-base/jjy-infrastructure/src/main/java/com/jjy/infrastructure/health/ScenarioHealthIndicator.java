package com.jjy.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M4 场景执行器健康检查。
 *
 * 阈值：
 *   - RUNNING + WAITING > 1000  → DOWN（场景实例堆积）
 *   - FAILED + TERMINATED > 50  → DOWN（失败场景过多需人工介入）
 *   - 否则 → UP
 *
 * @author jjy-base
 */
@Slf4j
@Component("scenario")
public class ScenarioHealthIndicator implements HealthIndicator {

    private static final int ACTIVE_THRESHOLD   = 1000;
    private static final int FAILED_THRESHOLD   = 50;

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public Health health() {
        try {
            Integer active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scenario_instance WHERE status IN ('RUNNING','WAITING')",
                    Integer.class);
            Integer completed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scenario_instance WHERE status = 'COMPLETED'",
                    Integer.class);
            Integer failed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scenario_instance WHERE status IN ('FAILED','TERMINATED')",
                    Integer.class);
            int a = active == null ? 0 : active;
            int c = completed == null ? 0 : completed;
            int f = failed == null ? 0 : failed;

            Health.Builder b;
            if (a > ACTIVE_THRESHOLD) {
                b = Health.down().withDetail("reason", "运行中场景超过阈值 " + ACTIVE_THRESHOLD);
            } else if (f > FAILED_THRESHOLD) {
                b = Health.down().withDetail("reason", "失败/终止场景超过阈值 " + FAILED_THRESHOLD);
            } else {
                b = Health.up();
            }

            return b.withDetail("running_or_waiting", a)
                    .withDetail("completed", c)
                    .withDetail("failed_or_terminated", f)
                    .withDetail("active_threshold", ACTIVE_THRESHOLD)
                    .withDetail("failed_threshold", FAILED_THRESHOLD)
                    .build();
        } catch (Exception e) {
            log.warn("ScenarioHealthIndicator 查询失败", e);
            return Health.unknown().withException(e).build();
        }
    }
}
