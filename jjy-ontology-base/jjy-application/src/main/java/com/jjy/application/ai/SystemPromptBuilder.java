package com.jjy.application.ai;

import com.jjy.domain.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统提示构建器：把 M1-M11 模型摘要 + 角色上下文 + 工具列表 + 业务规则压成 system prompt。
 *
 * <p>输出是一个完整的 OpenAI messages[0].system 字符串。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class SystemPromptBuilder {

    @Autowired
    private ModelRegistry modelRegistry;

    /**
     * 系统名称（写入 AI 的自我介绍）。
     *
     * <p>底座不硬编码任何业务系统名——由业务工程在 application.yml 里配置
     * {@code jjy.ai.system-name: 你的系统名}。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${jjy.ai.system-name:}")
    private String systemName;

    /**
     * 构建系统提示
     *
     * @param userContext  当前用户上下文（userId / role / customerId / permissions 等）
     * @return system prompt 字符串
     */
    public String build(Map<String, Object> userContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ").append(systemName()).append(" 的 AI 助手。\n");
        sb.append("你的核心职责：\n");
        sb.append("1. 帮助用户理解系统数据（").append(aggregateSummary()).append("）\n");
        sb.append("2. 引导用户完成业务操作（用工具查询、跳转页面、触发行为）\n");
        sb.append("3. 严格遵守调用方权限（你看到的数据 = 调用方能看到的）\n");
        sb.append("4. SQL 工具只能在表白名单内查询，禁止 INSERT/UPDATE/DELETE\n");
        sb.append("5. 不能直接修改任何数据，只能调 `behavior_*` 工具触发受控行为\n\n");

        sb.append("## 可用工具\n");
        sb.append("- `navigation_open(path, title)`: 跳转到系统页面（仅记录跳转意图，不实际打开浏览器）\n");
        sb.append("- `query_lookup(target, params)`: 通用元数据查询（字典、用户、角色、权限、菜单）\n");
        sb.append("- `behavior_describe(behaviorId)`: 描述 M2 行为的输入参数与规则\n");
        sb.append("- `sql_readonly(query, params)`: 执行只读 SQL（表白名单内）\n\n");

        sb.append("## 系统能力摘要\n");
        sb.append("模型版本: ").append(modelRegistryVersion()).append("\n");
        sb.append("聚合根: ").append(countOrNA(modelRegistry.getAggregateNames())).append(" 个\n");
        sb.append("行为: ").append(modelRegistry.getBehaviors().size()).append(" 个\n");
        sb.append("规则: ").append(modelRegistry.getRules().size()).append(" 条\n");
        sb.append("事件: ").append(modelRegistry.getEvents().size()).append(" 个\n");
        sb.append("流程: ").append(modelRegistry.getFlows().size()).append(" 个\n");
        sb.append("场景: ").append(modelRegistry.getScenarios().size()).append(" 个\n");
        sb.append("角色: ").append(modelRegistry.getRoles().size()).append(" 个\n");
        sb.append("权限: ").append(modelRegistry.getPermissions().size()).append(" 个\n");
        sb.append("查询报表: ").append(modelRegistry.getQueryReports().size()).append(" 个\n");
        sb.append("接口: ").append(modelRegistry.getInterfaces().size()).append(" 个\n\n");

        sb.append("## 当前用户上下文\n");
        if (userContext != null && !userContext.isEmpty()) {
            for (Map.Entry<String, Object> e : userContext.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        } else {
            sb.append("（无）\n");
        }
        sb.append("\n## 回复风格\n");
        sb.append("- 中文回答；技术名词保留英文\n");
        sb.append("- 不要捏造数据，没有数据时说\"未查询到\"\n");
        sb.append("- 调用工具前简要说明意图\n");
        sb.append("- 涉及业务操作前必须二次确认\n");
        return sb.toString();
    }

    /**
     * 系统名称：取配置项 {@code jjy.ai.system-name}，缺省回退到通用名。
     *
     * <p>提示词里的业务域描述来自 M1 模型（聚合根名称），不在代码里硬编码——
     * 这样业务工程换模型后，AI 的自我介绍自动跟着变。</p>
     */
    private String systemName() {
        String name = systemName;
        return (name == null || name.trim().isEmpty()) ? "本" : name;
    }

    /**
     * 业务域描述：取 M1 的聚合根名称，用于告诉 AI「这个系统管什么」。
     */
    private String aggregateSummary() {
        Map<String, String> names = modelRegistry.getAggregateNames();
        if (names == null || names.isEmpty()) {
            return "业务数据";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String v : names.values()) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(v);
            if (++i >= 8) {
                sb.append(" 等");
                break;
            }
        }
        return sb.toString();
    }

    private String modelRegistryVersion() {
        return "V1.0";
    }

    private String countOrNA(Map<?, ?> m) {
        return m == null ? "0" : String.valueOf(m.size());
    }
}
