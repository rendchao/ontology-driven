package com.jjy.application.ai;

import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具：通用元数据查询（字典、行为、角色、权限、菜单）。
 *
 * <p><b>本实现体现「模型是唯一语义源」：</b>除用户表这一类「运行期事实数据」外，
 * 所有元数据查询都直接读 {@link ModelRegistry}——即模型文件本身，
 * 而<b>不是</b>把表名列名硬编码成 SQL 去查数据库。</p>
 *
 * <p>为什么不能硬编码 SQL：模型一改（字典项改名、菜单加字段、权限换 targetRef），
 * 硬编码的 SQL 就会与真实表结构不一致，而这种不一致在编译期无法被发现，
 * 只会在 AI 调用到那一条数据时抛 SQL 异常。</p>
 *
 * <p>唯一的例外是 {@code user}：用户是运行期数据（M5 只声明角色与权限，
 * 用户实体在 DB），且列名由 MM 映射模型保证，查询仍然走真实表。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class QueryTool implements AiTool {

    /** 支持的查询目标 */
    private static final List<String> TARGETS = java.util.Arrays.asList(
            "dictionary", "dictionary_items", "user", "role", "permission", "menu", "behavior");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ModelRegistry modelRegistry;

    @Override
    public String name() {
        return "query_lookup";
    }

    @Override
    public String description() {
        return "查询系统元数据。支持 target: dictionary / dictionary_items / user / role / permission / menu / behavior。"
                + "字典、角色、权限、菜单、行为均直接来自本体模型（始终与模型一致）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> target = new HashMap<>();
        target.put("type", "string");
        target.put("enum", TARGETS);
        target.put("description", "查询目标类型");

        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        params.put("description", "查询参数（不同 target 不同）；"
                + "dictionary_items → {dictionary_id}；user → {keyword}");

        Map<String, Object> properties = new HashMap<>();
        properties.put("target", target);
        properties.put("params", params);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("target"));
        return schema;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args, Map<String, Object> userContext) {
        String target = (String) args.get("target");
        Map<String, Object> params = (Map<String, Object>) args.getOrDefault("params", new HashMap<>());

        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("不支持的 target: " + target);
        }
        log.info("[AiTool:query] user={} target={}",
                SecurityUtils.currentUser() == null ? "anonymous" : SecurityUtils.currentUser().getUsername(),
                target);

        List<Map<String, Object>> rows = query(target, params);
        Map<String, Object> out = new HashMap<>();
        out.put("target", target);
        out.put("count", rows.size());
        out.put("rows", rows);
        return out;
    }

    /**
     * 按目标分发查询。模型类目标走注册表，用户走真实表。
     */
    private List<Map<String, Object>> query(String target, Map<String, Object> params) {
        switch (target) {
            case "dictionary":
                return dictionaries();
            case "dictionary_items":
                return dictionaryItems(params);
            case "role":
                return roles();
            case "permission":
                return permissions();
            case "menu":
                return menus();
            case "behavior":
                return behaviors();
            case "user":
                return users(params);
            default:
                throw new IllegalArgumentException("不支持的 target: " + target);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  以下全部读模型注册表 —— 模型即数据源
    // ══════════════════════════════════════════════════════════

    /** 数据字典：来自 M1 的 dataDictionaries */
    private List<Map<String, Object>> dictionaries() {
        Map<String, Map<String, Object>> grouped = new HashMap<>();
        for (Object itemObj : modelRegistry.getDataDictionaryItems().values()) {
            if (!(itemObj instanceof Map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) itemObj;
            String dictId = str(item.get("dictionaryId"));
            Map<String, Object> dict = grouped.computeIfAbsent(dictId, k -> {
                Map<String, Object> d = new HashMap<>();
                d.put("dictionaryId", dictId);
                d.put("types", new ArrayList<String>());
                d.put("itemCount", 0);
                return d;
            });
            @SuppressWarnings("unchecked")
            List<String> types = (List<String>) dict.get("types");
            String typeCode = str(item.get("typeCode"));
            if (typeCode != null && !types.contains(typeCode)) {
                types.add(typeCode);
            }
            dict.put("itemCount", ((Number) dict.get("itemCount")).intValue() + 1);
        }
        return new ArrayList<>(grouped.values());
    }

    /** 字典项：按 dictionaryId 过滤 */
    private List<Map<String, Object>> dictionaryItems(Map<String, Object> params) {
        String dictId = str(params.get("dictionary_id"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object itemObj : modelRegistry.getDataDictionaryItems().values()) {
            if (!(itemObj instanceof Map)) {
                continue;
            }
            Map<String, Object> item = (Map<String, Object>) itemObj;
            if (dictId != null && !dictId.equals(str(item.get("dictionaryId")))) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /** 角色：来自 M5 */
    private List<Map<String, Object>> roles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object roleObj : modelRegistry.getRoles().values()) {
            if (roleObj instanceof Map) {
                out.add((Map<String, Object>) roleObj);
            }
        }
        return out;
    }

    /** 权限：来自 M5 */
    private List<Map<String, Object>> permissions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object permObj : modelRegistry.getPermissions().values()) {
            if (permObj instanceof Map) {
                out.add((Map<String, Object>) permObj);
            }
        }
        return out;
    }

    /** 菜单：来自 MU */
    private List<Map<String, Object>> menus() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> menus = modelRegistry.getMenus();
        if (menus == null) {
            return out;
        }
        for (Object menuObj : menus) {
            if (menuObj instanceof Map) {
                Map<String, Object> menu = new HashMap<>((Map<String, Object>) menuObj);
                Object children = menu.get("children");
                menu.put("children", children == null ? new ArrayList<>() : children);
                out.add(menu);
            }
        }
        return out;
    }

    /** 行为：来自 M2 */
    private List<Map<String, Object>> behaviors() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Behavior> e : modelRegistry.getBehaviors().entrySet()) {
            Behavior b = e.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("id", b.getId());
            item.put("name", b.getName());
            item.put("ownerEntity", b.getOwnerEntity());
            item.put("behaviorType", b.getBehaviorType());
            item.put("triggerType", b.getTriggerType());
            item.put("requiredPermissions", b.getRequiredPermissions());
            item.put("appliedRules", b.getAppliedRules());
            item.put("producedEvents", b.getProducedEvents());
            item.put("subscribedEvents", b.getSubscribedEvents());
            out.add(item);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    //  用户是运行期数据，走真实表
    // ══════════════════════════════════════════════════════════

    /**
     * 查询用户。
     *
     * <p>列名与 {@code t_user} 的真实结构一致（{@code full_name} 而非 {@code real_name}）。
     * 表结构由 MM 映射模型 + 迁移脚本保证，此处不做列名猜测。</p>
     */
    private List<Map<String, Object>> users(Map<String, Object> params) {
        String kw = str(params.get("keyword"));
        if (kw == null) {
            kw = "%";
        } else if (!kw.contains("%")) {
            kw = "%" + kw + "%";
        }
        String sql = "SELECT user_id, username, full_name, user_type, status "
                + "FROM t_user WHERE flag = 0 AND (username LIKE ? OR full_name LIKE ?) "
                + "ORDER BY username LIMIT 50";
        return jdbcTemplate.queryForList(sql, kw, kw);
    }

    private String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
