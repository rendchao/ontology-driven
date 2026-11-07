package com.jjy.application.flow;

import com.jjy.infrastructure.flow.FlowInstancePO;
import com.jjy.infrastructure.flow.FlowInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * M6 Flow 启动恢复器。
 *
 * <p>P5 范围内 flow 是同步走完型（startSync），理论上不会留 RUNNING 状态。
 * 但保险起见，启动时也扫一下：</p>
 * <ul>
 *   <li>RUNNING：调 forceUpdate 标 FAILED（异常路径）</li>
 *   <li>WAITING：留 P5.5 扩展（暂时只打日志）</li>
 * </ul>
 *
 * <p>放在 @Order(250)，晚于 ScenarioRecoveryRunner（200），避免互相干扰。</p>
 */
@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
@Order(250)
public class FlowRecoveryRunner implements ApplicationRunner {

    @Autowired
    private FlowInstanceService flowInstanceService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<FlowInstancePO> active = flowInstanceService.findActive();
            if (active.isEmpty()) {
                log.info("[FlowRecovery] 无遗留活动实例");
                return;
            }
            int running = 0, waiting = 0, failed = 0;
            for (FlowInstancePO po : active) {
                if ("RUNNING".equals(po.getStatus())) {
                    running++;
                    // 同步 flow 不会留 RUNNING；如果留了，标 FAILED + 错误信息
                    flowInstanceService.forceUpdate(po.getId(),
                            com.jjy.infrastructure.flow.FlowStatus.FAILED,
                            po.getCurrentActivity(),
                            po.getContext(),
                            "应用重启时残留 RUNNING 实例，已强制终止");
                    failed++;
                    log.warn("[FlowRecovery] 残留 RUNNING 已终止: id={}, flowId={}, lastActivity={}",
                            po.getId(), po.getFlowId(), po.getCurrentActivity());
                } else if ("WAITING".equals(po.getStatus())) {
                    waiting++;
                    log.info("[FlowRecovery] WAITING 留待 P5.5 扩展: id={}, flowId={}, currentActivity={}",
                            po.getId(), po.getFlowId(), po.getCurrentActivity());
                }
            }
            log.info("[FlowRecovery] 启动恢复汇总: running={}, waiting={}, failed={}",
                    running, waiting, failed);
        } catch (Exception e) {
            log.error("[FlowRecovery] 启动恢复异常", e);
        }
    }
}
