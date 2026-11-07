package com.jjy.application.scenario;

import com.jjy.application.behavior.BehaviorContext;
import com.jjy.application.behavior.BehaviorDispatcher;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioInstanceService;
import com.jjy.infrastructure.scenario.ScenarioStatus;
import com.jjy.infrastructure.scenario.ScenarioStepType;
import com.jjy.application.scenario.step.StepHandler;
import com.jjy.application.scenario.step.StepResult;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 场景执行器：场景实例化、步骤调度、状态机推进。
 *
 * <p>调度流程（参考 ontology-driven-dev §3.4）：
 * <ol>
 *   <li>从 M4 模型取场景定义（m4-scenario-model.yaml，已在 ModelRegistry.scenarios）</li>
 *   <li>按 steps 顺序或 nextStepId/branchTargetStep 选择当前 step</li>
 *   <li>按 stepType 找 StepHandler 执行</li>
 *   <li>根据 StepResult 推进状态：ADVANCE→下一步、WAIT→WAITING、BRANCH→跳 step、TERMINATE/COMPENSATE/FAIL→终态</li>
 * </ol>
 *
 * <p>对外 3 个入口：
 * <ul>
 *   <li>{@link #startStandalone} — EventBridge 触发（STANDALONE 场景）</li>
 *   <li>{@link #startSync} — M6 流程 SCENARIO_CALL 同步阻塞调用（EMBEDDED 场景）</li>
 *   <li>{@link #resume} — 等待中的场景被 EventBridge/TimeoutScanner 唤醒后继续</li>
 * </ul>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class ScenarioExecutor {

    @Autowired
    private ModelRegistry modelRegistry;

    @Autowired
    private ScenarioInstanceService scenarioInstanceService;

    @Autowired
    private List<StepHandler> stepHandlers;

    @Autowired
    private BehaviorDispatcher behaviorDispatcher;

    private final Map<ScenarioStepType, StepHandler> handlerMap = new HashMap<>();

    /**
     * 启动 STANDALONE 场景（异步，不阻塞调用方；EventBridge 触发）
     *
     * @param scenarioId 场景 ID
     * @param eventPayload 触发事件 payload
     * @return 新建的实例 ID；场景不存在返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public String startStandalone(String scenarioId, Map<String, Object> eventPayload) {
        Map<String, Object> def = (Map<String, Object>) modelRegistry.getScenarios().get(scenarioId);
        if (def == null) {
            log.warn("[Scenario] startStandalone 场景不存在: {}", scenarioId);
            return null;
        }
        String orchestration = (String) def.get("orchestration");
        if (!"STANDALONE".equalsIgnoreCase(orchestration)) {
            log.warn("[Scenario] startStandalone 只能启动 STANDALONE 场景: {} (orchestration={})", scenarioId, orchestration);
            return null;
        }
        Map<String, Object> ctx = new HashMap<>();
        if (eventPayload != null) ctx.putAll(eventPayload);
        ctx.put("_entryEvent", def.get("entryEvent"));
        ScenarioInstancePO po = scenarioInstanceService.create(scenarioId, ctx);
        // 异步推进
        runUntilWait(po.getId());
        return po.getId();
    }

    /**
     * 同步阻塞启动（EMBEDDED_IN_FLOW，由 M6 SCENARIO_CALL 活动调用）
     *
     * @return 终态实例；null 表示场景不存在
     */
    public ScenarioInstancePO startSync(String scenarioId, Map<String, Object> initialContext) {
        @SuppressWarnings("unchecked")
        Map<String, Object> def = (Map<String, Object>) modelRegistry.getScenarios().get(scenarioId);
        if (def == null) {
            log.warn("[Scenario] startSync 场景不存在: {}", scenarioId);
            return null;
        }
        Map<String, Object> ctx = initialContext == null ? new HashMap<>() : new HashMap<>(initialContext);
        ScenarioInstancePO po = scenarioInstanceService.create(scenarioId, ctx);
        return runUntilTerminal(po.getId());
    }

    /**
     * 唤醒 WAITING 中的场景继续执行（由 EventBridge 调用）
     *
     * @param instanceId 实例 ID
     * @param eventPayload 触发事件 payload（写入 context）
     */
    @SuppressWarnings("unchecked")
    public void resume(String instanceId, Map<String, Object> eventPayload) {
        ScenarioInstancePO po = scenarioInstanceService.get(instanceId);
        if (po == null) {
            log.warn("[Scenario] resume 实例不存在: {}", instanceId);
            return;
        }
        if (!"WAITING".equals(po.getStatus())) {
            log.info("[Scenario] resume 实例已不在 WAITING: id={}, status={}", instanceId, po.getStatus());
            return;
        }
        // 把事件 payload 合并进 context，清掉 waitingEventRef
        Map<String, Object> ctx = scenarioInstanceService.readContext(po);
        if (eventPayload != null) {
            ctx.putAll(eventPayload);
        }
        ctx.remove("waitingEventRef");
        ctx.remove("waitingDeadline");
        ctx.put("lastResumedAt", System.currentTimeMillis());
        // 切到 RUNNING：当前 step（EVENT_WAIT）已收到事件，视为完成，需推进到下一步
        Map<String, Object> def = (Map<String, Object>) modelRegistry.getScenarios().get(po.getScenarioId());
        String nextStep = advanceToNextStep(def, po.getCurrentStep());
        scenarioInstanceService.updateState(instanceId, ScenarioStatus.WAITING, ScenarioStatus.RUNNING,
                nextStep != null ? nextStep : po.getCurrentStep(), ctx, null);
        runUntilWait(instanceId);
    }

    /**
     * 给定当前 stepId，从 scenario 定义中找出下一步的 stepId；
     * 若当前已是最后一步返回 null。
     */
    private String advanceToNextStep(Map<String, Object> scenario, String currentStepId) {
        if (scenario == null || currentStepId == null) return null;
        List<Map<String, Object>> steps = (List<Map<String, Object>>) scenario.get("steps");
        if (steps == null || steps.isEmpty()) return null;
        for (int i = 0; i < steps.size() - 1; i++) {
            if (currentStepId.equals(steps.get(i).get("stepId"))) {
                return (String) steps.get(i + 1).get("stepId");
            }
        }
        return null;
    }

    /**
     * 推进到下一个等待点（WAITING 或终态）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public void runUntilWait(String instanceId) {
        ensureHandlerMap();
        ScenarioInstancePO po = scenarioInstanceService.get(instanceId);
        if (po == null) return;
        Map<String, Object> def = (Map<String, Object>) modelRegistry.getScenarios().get(po.getScenarioId());
        if (def == null) {
            markTerminal(instanceId, ScenarioStatus.FAILED, "场景定义不存在: " + po.getScenarioId());
            return;
        }
        List<Map<String, Object>> steps = (List<Map<String, Object>>) def.get("steps");
        if (steps == null || steps.isEmpty()) {
            markTerminal(instanceId, ScenarioStatus.COMPLETED, "无 step 定义，直接结束");
            return;
        }
        // 找到当前 step 索引
        int idx = findStepIndex(steps, po.getCurrentStep());
        if (idx < 0) idx = 0;
        Map<String, Object> ctx = scenarioInstanceService.readContext(po);
        int safetyCounter = 0;
        while (idx < steps.size()) {
            if (++safetyCounter > 50) {
                log.error("[Scenario] 安全计数器触发，疑似死循环: id={}", instanceId);
                markTerminal(instanceId, ScenarioStatus.FAILED, "执行超过 50 步仍未结束（疑似死循环）");
                return;
            }
            Map<String, Object> step = steps.get(idx);
            String stepTypeStr = (String) step.get("stepType");
            ScenarioStepType st;
            try {
                st = ScenarioStepType.valueOf(stepTypeStr);
            } catch (Exception e) {
                markTerminal(instanceId, ScenarioStatus.FAILED, "未知 stepType: " + stepTypeStr);
                return;
            }
            StepHandler handler = handlerMap.get(st);
            if (handler == null) {
                markTerminal(instanceId, ScenarioStatus.FAILED, "未注册 step handler: " + st);
                return;
            }
            // 每次重读 PO（防止 handler 已更新）
            ScenarioInstancePO current = scenarioInstanceService.get(instanceId);
            if (current == null) return;
            // 写入 currentStep
            if (!scenarioInstanceService.updateState(instanceId, ScenarioStatus.RUNNING, ScenarioStatus.RUNNING,
                    (String) step.get("stepId"), ctx, null)) {
                log.warn("[Scenario] 状态机冲突（实例非 RUNNING），终止: id={}", instanceId);
                return;
            }
            StepResult sr;
            try {
                sr = handler.handle(current, def, step);
            } catch (Exception e) {
                log.error("[Scenario] step handler 抛异常: id={}, step={}", instanceId, step.get("stepId"), e);
                sr = StepResult.fail("step handler 异常: " + e.getMessage());
            }
            // 合并 delta 到 context
            if (sr.getContextDelta() != null && !sr.getContextDelta().isEmpty()) {
                ctx.putAll(sr.getContextDelta());
            }
            switch (sr.getStatus()) {
                case ADVANCE:
                    idx++;
                    break;
                case BRANCH:
                    String target = sr.getBranchTargetStep();
                    if (target == null) {
                        idx++;
                    } else {
                        int nextIdx = findStepIndex(steps, target);
                        if (nextIdx < 0) {
                            markTerminal(instanceId, ScenarioStatus.FAILED, "branch targetStep 找不到: " + target);
                            return;
                        }
                        idx = nextIdx;
                    }
                    break;
                case WAIT:
                    // 切到 WAITING 状态（context 已含 waitingEventRef/waitingDeadline）
                    scenarioInstanceService.updateState(instanceId, ScenarioStatus.RUNNING, ScenarioStatus.WAITING,
                            (String) step.get("stepId"), ctx, null);
                    log.info("[Scenario] 进入 WAITING: id={}, step={}, event={}",
                            instanceId, step.get("stepId"), ctx.get("waitingEventRef"));
                    return;
                case TERMINATE:
                    markTerminal(instanceId, ScenarioStatus.TERMINATED, sr.getErrorMessage());
                    return;
                case COMPENSATE:
                    doCompensate(instanceId, current, def, sr.getCompensateBehavior(), ctx);
                    return;
                case FAIL:
                default:
                    markTerminal(instanceId, ScenarioStatus.FAILED, sr.getErrorMessage());
                    return;
            }
        }
        // 走完所有 step：COMPLETED
        markTerminal(instanceId, ScenarioStatus.COMPLETED, null);
    }

    /**
     * 同步推进到终态（用于 startSync）。
     * WAITING 时阻塞 poll 直到 EventBridge/TimeoutScanner 推进它；上限 30 分钟防卡死。
     */
    public ScenarioInstancePO runUntilTerminal(String instanceId) {
        runUntilWait(instanceId);
        long deadline = System.currentTimeMillis() + 30 * 60 * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ScenarioInstancePO po = scenarioInstanceService.get(instanceId);
            if (po == null) return null;
            ScenarioStatus s;
            try {
                s = ScenarioStatus.valueOf(po.getStatus());
            } catch (Exception e) {
                return po;
            }
            if (s.isTerminal()) return po;
            if ("RUNNING".equals(po.getStatus())) {
                runUntilWait(instanceId);
                continue;
            }
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return scenarioInstanceService.get(instanceId);
    }

    @SuppressWarnings("unchecked")
    private void doCompensate(String instanceId, ScenarioInstancePO current, Map<String, Object> def,
                              String behaviorId, Map<String, Object> ctx) {
        if (behaviorId == null) {
            markTerminal(instanceId, ScenarioStatus.TERMINATED, "onTimeout=COMPENSATE 但未指定 onTimeoutBehavior");
            return;
        }
        try {
            BehaviorContext bc = BehaviorContext.of(behaviorId);
            bc.setInput(new HashMap<>(ctx));
            bc.setAggregateId(ctx.get("aggregateId") == null ? null : ctx.get("aggregateId").toString());
            bc.setActor(SecurityUtils.currentUser());
            behaviorDispatcher.execute(bc);
            markTerminal(instanceId, ScenarioStatus.COMPENSATED, "补偿行为已执行: " + behaviorId);
        } catch (Exception e) {
            log.error("[Scenario] 补偿行为执行失败: id={}, behavior={}", instanceId, behaviorId, e);
            markTerminal(instanceId, ScenarioStatus.FAILED, "补偿失败: " + e.getMessage());
        }
    }

    private void markTerminal(String instanceId, ScenarioStatus terminal, String errorMsg) {
        ScenarioInstancePO po = scenarioInstanceService.get(instanceId);
        if (po == null) return;
        if (ScenarioStatus.valueOf(po.getStatus()).isTerminal()) {
            log.info("[Scenario] 实例已是终态，跳过: id={}, status={}", instanceId, po.getStatus());
            return;
        }
        scenarioInstanceService.updateState(instanceId, ScenarioStatus.RUNNING, terminal, po.getCurrentStep(),
                scenarioInstanceService.readContext(po), errorMsg);
        log.info("[Scenario] 终态: id={}, status={}, err={}", instanceId, terminal, errorMsg);
    }

    private int findStepIndex(List<Map<String, Object>> steps, String stepId) {
        if (stepId == null) return -1;
        for (int i = 0; i < steps.size(); i++) {
            if (stepId.equals(steps.get(i).get("stepId"))) return i;
        }
        return -1;
    }

    private synchronized void ensureHandlerMap() {
        if (handlerMap.isEmpty()) {
            for (StepHandler h : stepHandlers) {
                handlerMap.put(h.supportedType(), h);
            }
            log.info("[Scenario] step handler 初始化: {}", handlerMap.keySet());
        }
    }
}
