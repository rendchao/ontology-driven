package com.jjy.infrastructure.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.infrastructure.persistence.mapper.RuleFireLogMapper;
import com.jjy.infrastructure.persistence.po.RuleFireLogPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * M3 规则触发日志服务
 *
 * <p>关键设计：所有 {@code record*()} 方法都用 {@link Propagation#REQUIRES_NEW}，
 * 让规则触发的审计行与业务事务隔离 —— 即便业务最终因 {@code RuleViolationException} 回滚，
 * 本表的记录依然保留（业务白盒化）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class RuleFireLogService {

    @Autowired
    private RuleFireLogMapper ruleFireLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 记录 SKIP —— 非 VALIDATION 类型的规则在 dispatcher 里被 continue 时调用
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSkip(String behaviorId, String behaviorName, String aggregateType,
                           String aggregateId, String ruleId, String ruleName,
                           String ruleType, String engine, String expression,
                           String tenantId, String actorUserId, String traceId) {
        try {
            RuleFireLogPO po = basePo(behaviorId, behaviorName, aggregateType, aggregateId,
                    ruleId, ruleName, ruleType, engine, expression,
                    tenantId, actorUserId, traceId);
            po.setResult("SKIP");
            po.setOutput(null);
            po.setInputParams(null);
            po.setViolationMessage(null);
            ruleFireLogMapper.insert(po);
        } catch (Exception e) {
            // 审计失败不影响主业务
            log.warn("[RuleFireLog] recordSkip failed: ruleId={}, err={}", ruleId, e.getMessage());
        }
    }

    /**
     * 记录 PASS —— 评估通过
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordPass(String behaviorId, String behaviorName, String aggregateType,
                           String aggregateId, String ruleId, String ruleName,
                           String ruleType, String engine, String expression,
                           Map<String, Object> inputParams,
                           String tenantId, String actorUserId, String traceId) {
        try {
            RuleFireLogPO po = basePo(behaviorId, behaviorName, aggregateType, aggregateId,
                    ruleId, ruleName, ruleType, engine, expression,
                    tenantId, actorUserId, traceId);
            po.setResult("PASS");
            po.setOutput(1);
            po.setInputParams(serializeParams(inputParams));
            po.setViolationMessage(null);
            ruleFireLogMapper.insert(po);
        } catch (Exception e) {
            log.warn("[RuleFireLog] recordPass failed: ruleId={}, err={}", ruleId, e.getMessage());
        }
    }

    /**
     * 记录 FAIL —— 评估不通过（在 throw RuleViolationException 之前调用，让违规行能落库）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFail(String behaviorId, String behaviorName, String aggregateType,
                           String aggregateId, String ruleId, String ruleName,
                           String ruleType, String engine, String expression,
                           Map<String, Object> inputParams, String violationMessage,
                           String tenantId, String actorUserId, String traceId) {
        try {
            RuleFireLogPO po = basePo(behaviorId, behaviorName, aggregateType, aggregateId,
                    ruleId, ruleName, ruleType, engine, expression,
                    tenantId, actorUserId, traceId);
            po.setResult("FAIL");
            po.setOutput(0);
            po.setInputParams(serializeParams(inputParams));
            po.setViolationMessage(violationMessage);
            ruleFireLogMapper.insert(po);
        } catch (Exception e) {
            log.warn("[RuleFireLog] recordFail failed: ruleId={}, err={}", ruleId, e.getMessage());
        }
    }

    private RuleFireLogPO basePo(String behaviorId, String behaviorName, String aggregateType,
                                 String aggregateId, String ruleId, String ruleName,
                                 String ruleType, String engine, String expression,
                                 String tenantId, String actorUserId, String traceId) {
        RuleFireLogPO po = new RuleFireLogPO();
        po.setFiredAt(LocalDateTime.now());
        po.setTenantId(tenantId);
        po.setBehaviorId(behaviorId);
        po.setBehaviorName(behaviorName);
        po.setAggregateType(aggregateType);
        po.setAggregateId(aggregateId);
        po.setRuleId(ruleId);
        po.setRuleName(ruleName);
        po.setRuleType(ruleType);
        po.setEngine(engine);
        po.setExpression(expression);
        po.setActorUserId(actorUserId);
        po.setTraceId(traceId);
        return po;
    }

    private String serializeParams(Map<String, Object> inputParams) {
        if (inputParams == null || inputParams.isEmpty()) return null;
        try {
            String json = objectMapper.writeValueAsString(inputParams);
            // 防止超长
            return json.length() > 4000 ? json.substring(0, 4000) : json;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ===== 查询（供 admin controller 用） =====

    /**
     * 分页查询：因项目未注册 MybatisPlusInterceptor 做自动 count，这里手动 count+list。
     */
    public Page<RuleFireLogPO> pageQuery(long pageNo, long pageSize,
                                          String ruleId, String behaviorId,
                                          String aggregateType, String aggregateId,
                                          String result, String ruleType) {
        LambdaQueryWrapper<RuleFireLogPO> q = new LambdaQueryWrapper<>();
        if (ruleId != null && !ruleId.isEmpty()) q.eq(RuleFireLogPO::getRuleId, ruleId);
        if (behaviorId != null && !behaviorId.isEmpty()) q.eq(RuleFireLogPO::getBehaviorId, behaviorId);
        if (aggregateType != null && !aggregateType.isEmpty()) q.eq(RuleFireLogPO::getAggregateType, aggregateType);
        if (aggregateId != null && !aggregateId.isEmpty()) q.eq(RuleFireLogPO::getAggregateId, aggregateId);
        if (result != null && !result.isEmpty()) q.eq(RuleFireLogPO::getResult, result);
        if (ruleType != null && !ruleType.isEmpty()) q.eq(RuleFireLogPO::getRuleType, ruleType);
        // 手动 count（避免依赖未注册的 MybatisPlusInterceptor）
        long total = ruleFireLogMapper.selectCount(q);
        // 列表（带 orderBy + 物理分页）
        q.orderByDesc(RuleFireLogPO::getFiredAt);
        q.last("LIMIT " + pageSize + " OFFSET " + (pageNo - 1) * pageSize);
        java.util.List<RuleFireLogPO> records = ruleFireLogMapper.selectList(q);
        Page<RuleFireLogPO> page = new Page<>(pageNo, pageSize);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    public long countByResult(String result) {
        LambdaQueryWrapper<RuleFireLogPO> q = new LambdaQueryWrapper<>();
        if (result != null && !result.isEmpty()) q.eq(RuleFireLogPO::getResult, result);
        return ruleFireLogMapper.selectCount(q);
    }
}
