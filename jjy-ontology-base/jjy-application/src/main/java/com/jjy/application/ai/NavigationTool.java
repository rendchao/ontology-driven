package com.jjy.application.ai;

import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 工具：导航到系统页面。
 *
 * <p>实际不打开浏览器；返回 `{ path, title, requiresPermission }` 给前端，
 * 前端按 `card` 事件类型渲染跳转链接。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class NavigationTool implements AiTool {

    @Override
    public String name() {
        return "navigation_open";
    }

    @Override
    public String description() {
        return "跳转到系统内一个页面（菜单/详情/表单）。返回跳转意图，前端渲染跳转按钮。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "目标页面路径，例如 /inbound/list 或 /inbound/detail?orderId=xxx");

        Map<String, Object> title = new HashMap<>();
        title.put("type", "string");
        title.put("description", "页面标题，用于前端显示");

        Map<String, Object> properties = new HashMap<>();
        properties.put("path", path);
        properties.put("title", title);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("path", "title"));
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> args, Map<String, Object> userContext) {
        String path = (String) args.get("path");
        String title = (String) args.get("title");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        log.info("[AiTool:navigation] user={} → {} ({})", SecurityUtils.currentUser().getUsername(), path, title);
        Map<String, Object> out = new HashMap<>();
        out.put("path", path);
        out.put("title", title);
        out.put("cardType", "navigation");
        out.put("instruction", "用户可点击下方按钮跳转到「" + title + "」");
        return out;
    }
}
