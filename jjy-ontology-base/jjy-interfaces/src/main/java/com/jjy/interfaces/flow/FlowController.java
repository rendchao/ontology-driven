package com.jjy.interfaces.flow;

import com.jjy.application.flow.FlowEngine;
import com.jjy.domain.flow.Flow;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.flow.FlowInstanceService;
import com.jjy.infrastructure.flow.FlowInstancePO;
import com.jjy.infrastructure.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程端点：对应 M6 流程模型。
 *
 * <p>流程定义来自 M6（{@code registry.getFlows()}），引擎负责按 nodeGraph 推进。
 * 业务工程通常不直接调本控制器，而是由 M2 行为在事务内调用 {@link FlowEngine} 启动流程。</p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/flow")
public class FlowController {

    @Autowired
    private ModelRegistry registry;

    @Autowired
    private FlowEngine flowEngine;

    @Autowired
    private FlowInstanceService flowInstanceService;

    /** 流程定义清单（来自 M6） */
    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> definitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        registry.getFlows().forEach((id, flow) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", flow.getId());
            item.put("name", flow.getName());
            item.put("flowType", flow.getFlowType());
            item.put("description", flow.getDescription());
            item.put("trigger", flow.getTrigger());
            item.put("startActivity", flow.getStartActivity());
            item.put("endActivities", flow.getEndActivities());
            item.put("roleRefs", flow.getRoleRefs());
            item.put("businessObjectRefs", flow.getBusinessObjectRefs());
            item.put("activities", flow.getActivities());
            out.add(item);
        });
        return R.ok(out);
    }

    /** 运行中的流程实例 */
    @GetMapping("/instances/active")
    public R<List<FlowInstancePO>> activeInstances() {
        return R.ok(flowInstanceService.findActive());
    }

    /** 启动一个流程实例 */
    @PostMapping("/instances")
    public R<FlowInstancePO> start(@RequestBody Map<String, Object> body) {
        String flowId = (String) body.get("flowId");
        if (flowId == null || flowId.trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "flowId 不能为空");
        }
        Flow flow = registry.getFlow(flowId);
        if (flow == null) {
            return R.fail(R.CODE_NOT_FOUND, "流程不存在: " + flowId);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.get("input");
        return R.ok(flowEngine.startSync(flowId, input == null ? new HashMap<>() : input), "流程已启动");
    }
}
