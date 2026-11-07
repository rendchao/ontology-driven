package com.jjy.application.scenario.step;

import com.jjy.infrastructure.scenario.ScenarioInstancePO;
import com.jjy.infrastructure.scenario.ScenarioStepType;

import java.util.Map;

/**
 * M4 场景步骤处理器接口。
 *
 * <p>每种 stepType 对应一个实现，由 {@link com.jjy.application.scenario.ScenarioExecutor} 通过 type 查找并执行。</p>
 *
 * @author jjy-base
 */
public interface StepHandler {

    /** 该 handler 负责的 step 类型 */
    ScenarioStepType supportedType();

    /**
     * 执行当前 step 并返回执行结果
     */
    StepResult handle(ScenarioInstancePO instance, Map<String, Object> scenario, Map<String, Object> step);
}
