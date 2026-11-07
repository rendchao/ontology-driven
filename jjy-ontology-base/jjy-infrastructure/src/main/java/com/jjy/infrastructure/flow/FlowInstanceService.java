package com.jjy.infrastructure.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M6 流程实例 CRUD + 状态机持久化（仿 ScenarioInstanceService）。
 *
 * <p>context 是同步过 flow_activity_instance 之间的 binding；每次活动完成都更新一次。</p>
 */
@Slf4j
@Service
public class FlowInstanceService {

    @Autowired
    private FlowInstanceMapper flowInstanceMapper;

    @Autowired
    private FlowActivityInstanceMapper activityMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 创建新实例（status=RUNNING，准备执行第一个 activity）
     */
    public FlowInstancePO create(String flowId, Map<String, Object> context) {
        FlowInstancePO po = new FlowInstancePO();
        po.setId(UUID.randomUUID().toString().replace("-", ""));
        po.setFlowId(flowId);
        po.setStatus(FlowStatus.RUNNING.name());
        po.setContext(context == null ? new HashMap<>() : context);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        flowInstanceMapper.insert(po);
        log.info("[Flow] 创建实例: id={}, flowId={}", po.getId(), flowId);
        return po;
    }

    public FlowInstancePO get(String id) {
        return flowInstanceMapper.selectById(id);
    }

    public List<FlowInstancePO> findActive() {
        return flowInstanceMapper.findActive();
    }

    /**
     * 乐观锁更新：仅当 oldStatus 匹配时更新
     */
    public boolean updateState(String id, FlowStatus oldStatus, FlowStatus newStatus,
                               String currentActivity, Map<String, Object> context, String errorMsg) {
        try {
            String ctxJson = objectMapper.writeValueAsString(context == null ? new HashMap<>() : context);
            int n = flowInstanceMapper.compareAndSetStatus(id, oldStatus.name(), newStatus.name(),
                    currentActivity, ctxJson, errorMsg);
            if (n == 0) {
                log.warn("[Flow] 状态变更失败（乐观锁或行不存在）: id={}, {} → {}", id, oldStatus, newStatus);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("[Flow] 状态变更异常: id={}", id, e);
            return false;
        }
    }

    /**
     * 直接覆盖更新（不校验 oldStatus，用于恢复场景的强制修正）
     */
    public void forceUpdate(String id, FlowStatus status, String currentActivity,
                            Map<String, Object> context, String errorMsg) {
        FlowInstancePO po = flowInstanceMapper.selectById(id);
        if (po == null) return;
        po.setStatus(status.name());
        po.setCurrentActivity(currentActivity);
        po.setContext(context == null ? new HashMap<>() : context);
        po.setErrorMsg(errorMsg);
        po.setUpdatedAt(LocalDateTime.now());
        flowInstanceMapper.updateById(po);
    }

    /**
     * 开始一个 activity：写 flow_activity_instance 一行，返回该行 id（供 finishActivity 用）
     */
    public Long startActivity(String flowInstanceId, String activityId, String activityType, String behaviorRef) {
        FlowActivityInstancePO ai = new FlowActivityInstancePO();
        ai.setFlowInstanceId(flowInstanceId);
        ai.setActivityId(activityId);
        ai.setActivityType(activityType);
        ai.setBehaviorRef(behaviorRef);
        ai.setStartedAt(LocalDateTime.now());
        activityMapper.insert(ai);
        return ai.getId();
    }

    /**
     * 结束一个 activity：写 result_status + finished_at；errorMsg 可选
     */
    public void finishActivity(Long activityInstanceId, String resultStatus, String errorMsg) {
        if (activityInstanceId == null) return;
        FlowActivityInstancePO ai = activityMapper.selectById(activityInstanceId);
        if (ai == null) return;
        ai.setResultStatus(resultStatus);
        ai.setFinishedAt(LocalDateTime.now());
        ai.setErrorMsg(errorMsg);
        activityMapper.updateById(ai);
    }
}
