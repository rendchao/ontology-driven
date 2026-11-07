package com.jjy.infrastructure.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * M4 场景实例 CRUD + 状态机持久化。
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class ScenarioInstanceService {

    @Autowired
    private ScenarioInstanceMapper mapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 创建新实例（status=RUNNING，准备执行第一个 step）
     */
    public ScenarioInstancePO create(String scenarioId, Map<String, Object> context) {
        ScenarioInstancePO po = new ScenarioInstancePO();
        po.setId(UUID.randomUUID().toString().replace("-", ""));
        po.setScenarioId(scenarioId);
        po.setStatus(ScenarioStatus.RUNNING.name());
        po.setContext(context == null ? new HashMap<>() : context);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        mapper.insert(po);
        log.info("[Scenario] 创建实例: id={}, scenarioId={}", po.getId(), scenarioId);
        return po;
    }

    public ScenarioInstancePO get(String id) {
        return mapper.selectById(id);
    }

    public List<ScenarioInstancePO> findActive() {
        return mapper.findActive();
    }

    public List<ScenarioInstancePO> findWaitingByEventRef(String eventRef) {
        return mapper.findWaitingByEventRef(eventRef);
    }

    public List<ScenarioInstancePO> findTimedOut() {
        return mapper.findTimedOut();
    }

    /**
     * 更新状态 + 上下文（带乐观锁：仅当 oldStatus 匹配时更新）
     */
    public boolean updateState(String id, ScenarioStatus oldStatus, ScenarioStatus newStatus,
                               String currentStep, Map<String, Object> context, String errorMsg) {
        try {
            String ctxJson = objectMapper.writeValueAsString(context == null ? new HashMap<>() : context);
            int n = mapper.compareAndSetStatus(id, oldStatus.name(), newStatus.name(), currentStep, ctxJson, errorMsg);
            if (n == 0) {
                log.warn("[Scenario] 状态变更失败（乐观锁或行不存在）: id={}, {} → {}", id, oldStatus, newStatus);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("[Scenario] 状态变更异常: id={}", id, e);
            return false;
        }
    }

    /**
     * 直接覆盖更新（不校验 oldStatus，用于恢复场景的强制修正）
     */
    public void forceUpdate(String id, ScenarioStatus status, String currentStep,
                            Map<String, Object> context, String errorMsg) {
        ScenarioInstancePO po = mapper.selectById(id);
        if (po == null) return;
        po.setStatus(status.name());
        po.setCurrentStep(currentStep);
        po.setContext(context == null ? new HashMap<>() : context);
        po.setErrorMsg(errorMsg);
        po.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(po);
    }

    public Map<String, Object> readContext(ScenarioInstancePO po) {
        if (po == null) return new HashMap<>();
        Map<String, Object> ctx = po.getContext();
        return ctx == null ? new HashMap<>() : ctx;
    }
}
