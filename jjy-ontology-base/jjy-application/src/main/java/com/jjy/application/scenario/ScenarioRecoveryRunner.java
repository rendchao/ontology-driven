package com.jjy.application.scenario;

import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M4 场景启动恢复器。
 *
 * <p>应用启动时（Flyway 跑完、所有 bean 就绪后）：
 * <ol>
 *   <li>列出所有 RUNNING + WAITING 实例（应用崩溃或重启时遗留）</li>
 *   <li>对 RUNNING：调用 ScenarioExecutor.runUntilWait 继续推进（如果 step 仍是 BEHAVIOR_CALL/RULE_CHECK/SUB_SCENARIO 等可重入步骤）</li>
 *   <li>对 WAITING：不做主动操作（等 EventBridge/TimeoutScanner 唤醒）；只做对账日志</li>
 * </ol>
 *
 * <p>注意：EventBridge 已经是 @Async + AFTER_COMMIT，重启后第一次事件触发时自动 resume 等待中的实例；本 runner 主要负责 RUNNING 中断的续跑。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
@Order(200)
public class ScenarioRecoveryRunner implements ApplicationRunner {

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Autowired
    private ScenarioExecutor scenarioExecutor;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ScenarioInstancePO> active = scenarioInstanceService.findActive();
            if (active.isEmpty()) {
                log.info("[ScenarioRecovery] 无遗留活动实例");
                return;
            }
            int running = 0, waiting = 0, recovered = 0, failed = 0;
            for (ScenarioInstancePO po : active) {
                if ("WAITING".equals(po.getStatus())) {
                    waiting++;
                    log.info("[ScenarioRecovery] WAITING 等待事件唤醒: id={}, scenario={}, step={}, waitingEvent={}",
                            po.getId(), po.getScenarioId(), po.getCurrentStep(), po.getContext().get("waitingEventRef"));
                    continue;
                }
                if ("RUNNING".equals(po.getStatus())) {
                    running++;
                    try {
                        scenarioExecutor.runUntilWait(po.getId());
                        recovered++;
                        log.info("[ScenarioRecovery] RUNNING 续跑完成: id={}, scenario={}, step={}",
                                po.getId(), po.getScenarioId(), po.getCurrentStep());
                    } catch (Exception e) {
                        failed++;
                        log.error("[ScenarioRecovery] RUNNING 续跑失败: id={}, scenario={}",
                                po.getId(), po.getScenarioId(), e);
                    }
                }
            }
            log.info("[ScenarioRecovery] 启动恢复汇总: running={}, waiting={}, recovered={}, failed={}",
                    running, waiting, recovered, failed);
        } catch (Exception e) {
            log.error("[ScenarioRecovery] 启动恢复异常", e);
        }
    }
}
