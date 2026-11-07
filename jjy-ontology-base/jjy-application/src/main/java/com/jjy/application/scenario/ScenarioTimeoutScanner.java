package com.jjy.application.scenario;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.behavior.BehaviorDispatcher;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import com.jjy.infrastructure.scenario.ScenarioStatus;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 场景超时扫描器：定时检查 WAITING 中但 waitingDeadline 已过的实例。
 *
 * <p>触发 onTimeout 处理：
 * <ul>
 *   <li>onTimeout=TERMINATE → 直接置 TERMINATED</li>
 *   <li>onTimeout=COMPENSATE → 调 onTimeoutBehavior（成功→COMPENSATED，失败→FAILED）</li>
 *   <li>onTimeout=null/空 → 按 TERMINATE 处理</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ScenarioTimeoutScanner {

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private ScenarioExecutor scenarioExecutor;

    @Autowired
    private BehaviorDispatcher behaviorDispatcher;

    @Value("${jjy.scenario.timeout-scan.fixed-delay-ms:60000}")
    private long fixedDelayMs;

    /**
     * 默认 1 分钟跑一次。可通过 jjy.scenario.timeout-scan.fixed-delay-ms 调整。
     * initialDelay 保证应用启动 30s 后第一次跑（避免与 OutboxDispatcher 抢资源）。
     */
    @Scheduled(initialDelayString = "30000", fixedDelayString = "${jjy.scenario.timeout-scan.fixed-delay-ms:60000}")
    public void scan() {
        try {
            // SQL 只初筛"WAITING + 有 deadline 候选"；真正的时间比较在 Java 里做
            // （见 ScenarioInstanceMapper#findTimedOut 的 javadoc）
            List<ScenarioInstancePO> candidates = scenarioInstanceService.findTimedOut();
            if (candidates.isEmpty()) return;
            LocalDateTime now = LocalDateTime.now();
            List<ScenarioInstancePO> timedOut = new ArrayList<>();
            for (ScenarioInstancePO po : candidates) {
                Map<String, Object> ctx = scenarioInstanceService.readContext(po);
                Object raw = ctx.get("waitingDeadline");
                if (raw == null) continue;
                try {
                    LocalDateTime deadline = LocalDateTime.parse(raw.toString());
                    if (deadline.isBefore(now)) {
                        timedOut.add(po);
                    }
                } catch (Exception parseErr) {
                    log.warn("[ScenarioTimeout] waitingDeadline 解析失败，跳过: instance={}, deadline={}", po.getId(), raw);
                }
            }
            if (timedOut.isEmpty()) return;
            log.info("[ScenarioTimeout] 扫描到 {} 个超时实例（候选 {} 个）", timedOut.size(), candidates.size());
            for (ScenarioInstancePO po : timedOut) {
                handleTimeout(po);
            }
        } catch (Exception e) {
            log.error("[ScenarioTimeout] 扫描异常", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleTimeout(ScenarioInstancePO po) {
        Map<String, Object> def = (Map<String, Object>) modelRegistry.getScenarios().get(po.getScenarioId());
        if (def == null) {
            log.warn("[ScenarioTimeout] 实例 {} 场景定义不存在: {}", po.getId(), po.getScenarioId());
            scenarioInstanceService.updateState(po.getId(), ScenarioStatus.WAITING, ScenarioStatus.FAILED,
                    po.getCurrentStep(), scenarioInstanceService.readContext(po), "场景定义不存在");
            return;
        }
        List<Map<String, Object>> steps = (List<Map<String, Object>>) def.get("steps");
        if (steps == null) {
            scenarioInstanceService.updateState(po.getId(), ScenarioStatus.WAITING, ScenarioStatus.FAILED,
                    po.getCurrentStep(), scenarioInstanceService.readContext(po), "无 step 定义");
            return;
        }
        // 找当前 step 的 onTimeout 配置
        Map<String, Object> currentStep = null;
        for (Map<String, Object> s : steps) {
            if (po.getCurrentStep() != null && po.getCurrentStep().equals(s.get("stepId"))) {
                currentStep = s;
                break;
            }
        }
        String onTimeout = currentStep == null ? null : (String) currentStep.get("onTimeout");
        String onTimeoutBehavior = currentStep == null ? null : (String) currentStep.get("onTimeoutBehavior");
        Map<String, Object> ctx = scenarioInstanceService.readContext(po);

        if ("COMPENSATE".equalsIgnoreCase(onTimeout) && onTimeoutBehavior != null) {
            // 切到 RUNNING 让 ScenarioExecutor 走补偿路径
            scenarioInstanceService.updateState(po.getId(), ScenarioStatus.WAITING, ScenarioStatus.RUNNING,
                    po.getCurrentStep(), ctx, null);
            // 用 executor 提供的内部补偿能力：直接调补偿 behavior 然后置 COMPENSATED
            try {
                BehaviorContext bc = BehaviorContext.of(onTimeoutBehavior);
                bc.setInput(new HashMap<>(ctx));
                bc.setAggregateId(ctx.get("aggregateId") == null ? null : ctx.get("aggregateId").toString());
                bc.setActor(SecurityUtils.currentUser());
                behaviorDispatcher.execute(bc);
                scenarioInstanceService.updateState(po.getId(), ScenarioStatus.RUNNING, ScenarioStatus.COMPENSATED,
                        po.getCurrentStep(), ctx, "超时补偿: " + onTimeoutBehavior);
                log.info("[ScenarioTimeout] 补偿完成: instance={}, behavior={}", po.getId(), onTimeoutBehavior);
            } catch (Exception e) {
                log.error("[ScenarioTimeout] 补偿行为失败: instance={}, behavior={}", po.getId(), onTimeoutBehavior, e);
                scenarioInstanceService.updateState(po.getId(), ScenarioStatus.RUNNING, ScenarioStatus.FAILED,
                        po.getCurrentStep(), ctx, "补偿失败: " + e.getMessage());
            }
        } else {
            // 默认：TERMINATE
            String timeoutSpec = currentStep == null ? "0s" : String.valueOf(currentStep.get("timeout"));
            String msg = "超时自动终止（step=" + (currentStep == null ? "null" : currentStep.get("stepId")) +
                    ", timeout=" + timeoutSpec + "）";
            scenarioInstanceService.updateState(po.getId(), ScenarioStatus.WAITING, ScenarioStatus.TERMINATED,
                    po.getCurrentStep(), ctx, msg);
            log.info("[ScenarioTimeout] 自动终止: instance={}, step={}", po.getId(), po.getCurrentStep());
        }
    }
}
