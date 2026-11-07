package com.jjy.interfaces.meta;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体元数据端点：把模型注册表的内容对外暴露。
 *
 * <p>这是「模型是唯一语义源」在接口层的体现——前端下拉框选项、菜单、按钮权限、
 * 行为清单全部来自这里，业务工程新增模型声明后前端无需改代码。</p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/meta")
public class MetaController {

    @Autowired
    private ModelRegistry registry;

    /** 数据字典：前端下拉框的数据源（对应 M1 data_dictionaries） */
    @GetMapping("/dictionaries")
    public R<List<Map<String, Object>>> dictionaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : registry.getDataDictionaryItems().values()) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return R.ok(out);
    }

    /**
     * 界面定义清单：对应 MU 模型的 {@code screens[]}。
     *
     * <p><b>为什么需要它</b>：MU 声明了每个界面的元素（字段、类型、必填、
     * 字典绑定）与操作点（对应哪个 M2 行为），但前端此前把这些写死了——
     * 改模型不生效，「模型是唯一语义源」在界面层断了。
     * 本接口把声明交出去，前端据此渲染表单与表格。</p>
     *
     * <p>每个 screen 的结构：</p>
     * <pre>
     * {
     *   screenId   : FrmProductMaintain,
     *   name       : 商品维护,
     *   screenType : MAINTAIN,
     *   layout     : SINGLE_TABLE,
     *   elements   : [ { id, type, label, io, required, dictionaryRef, dataBinding } ],
     *   actions    : [ { id, name, behaviorRef } ]
     * }
     * </pre>
     */
    @GetMapping("/screens")
    public R<Map<String, Object>> screens() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : registry.getScreens().entrySet()) {
            if (e.getValue() instanceof Map) {
                out.put(e.getKey(), normalizeScreen((Map<String, Object>) e.getValue()));
            }
        }
        return R.ok(out);
    }

    /**
     * 单个界面定义：按 screenId 取。
     *
     * <p>前端按路由对应的 screenId 拉取，渲染出该界面的表单字段与操作按钮。</p>
     */
    @GetMapping("/screens/{screenId}")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> screen(@PathVariable String screenId) {
        Object raw = registry.getScreens().get(screenId);
        if (!(raw instanceof Map)) {
            return R.fail(R.CODE_NOT_FOUND, "界面不存在: " + screenId);
        }
        return R.ok(normalizeScreen((Map<String, Object>) raw));
    }

    /**
     * 接口契约清单：对应 MI 模型的 {@code interfaces[]}。
     *
     * <p><b>为什么需要它</b>：MI 声明了每个外部接口的方法、路径、权限码、
     * 请求/响应字段——这是 REST 契约的唯一真源。此前这些声明<b>无人消费</b>：
     * 接口文档靠手写，权限码靠约定，请求/响应字段无人校验。
     * 本接口把契约交出去，让契约本身成为可查询的事实。</p>
     *
     * <p>用法：</p>
     * <ul>
     *   <li>生成 API 文档 / 供 AI 工具回答「有哪些接口」；</li>
     *   <li>{@code permissionCode} 可与 M5 的权限声明交叉核对；</li>
     *   <li>{@code requestFields} / {@code responseFields} 供契约测试与前端对接参考。</li>
     * </ul>
     */
    @GetMapping("/interfaces")
    public R<Map<String, Object>> interfaces() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : registry.getInterfaces().entrySet()) {
            if (e.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> itf = (Map<String, Object>) e.getValue();
                out.put(e.getKey(), normalizeInterface(itf));
            }
        }
        return R.ok(out);
    }

    /**
     * 归一化接口契约：把 {@code requestFields} / {@code responseFields}
     * 解析成结构化的字段清单。
     *
     * <p>此前这两个声明只是躺在 Map 里没人读——接口该收什么、该返回什么，
     * 全凭 handler 随手写。现在契约有唯一答案，前端与 AI 工具据此对接。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeInterface(Map<String, Object> src) {
        Map<String, Object> out = new HashMap<>(src);
        out.put("requestFieldList", fieldList(src.get("requestFields")));
        out.put("responseFieldList", fieldList(src.get("responseFields")));
        return out;
    }

    /**
     * 把字段声明列表解析为 { name, label, type, required } 清单。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fieldList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List)) {
            return out;
        }
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> f = (Map<String, Object>) o;
            Map<String, Object> item = new HashMap<>();
            item.put("name", f.get("name"));
            item.put("label", f.get("label"));
            item.put("type", f.get("type"));
            item.put("required", Boolean.TRUE.equals(f.get("required")));
            out.add(item);
        }
        return out;
    }

    /**
     * 归一化界面定义：把元素按 {@code io} 分成输入区与展示区。
     *
     * <p>前端据此分别渲染「表单字段」与「表格列」——
     * 不再在前端硬编码哪些字段是输入、哪些是展示。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeScreen(Map<String, Object> src) {
        Map<String, Object> out = new HashMap<>(src);
        List<Map<String, Object>> inputs = new ArrayList<>();
        List<Map<String, Object>> displays = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>();

        Object elsObj = src.get("elements");
        if (elsObj instanceof List) {
            for (Object e : (List<?>) elsObj) {
                if (!(e instanceof Map)) {
                    continue;
                }
                Map<String, Object> el = (Map<String, Object>) e;
                String io = el.get("io") == null ? "" : el.get("io").toString();
                if ("INPUT".equalsIgnoreCase(io)) {
                    inputs.add(el);
                } else {
                    displays.add(el);
                    // 表格列：显式读出列宽（MU 声明的 width），
                    // 前端据此设 el-table-column 的 width。
                    // 此前 width 只是躺在 Map 里没人读——声明了不生效。
                    Map<String, Object> col = new HashMap<>(el);
                    Object w = el.get("width");
                    col.put("width", w == null ? null : Integer.parseInt(w.toString().trim()));
                    columns.add(col);
                }
            }
        }
        out.put("inputElements", inputs);
        out.put("displayElements", displays);
        out.put("tableColumns", columns);

        // ── screenType：界面形态 ──
        // 前端据此决定渲染方式（QUERY 查询布局 / MAINTAIN 维护布局）。
        // 此前该声明只是躺在 Map 里没人读——改模型不生效。
        String screenType = src.get("screenType") == null ? "" : src.get("screenType").toString();
        out.put("screenType", screenType);
        out.put("isQueryScreen", "QUERY".equalsIgnoreCase(screenType));
        out.put("isMaintainScreen", "MAINTAIN".equalsIgnoreCase(screenType));

        // ── layout：布局策略 ──
        // SINGLE_TABLE 单表维护 / QUERY_RESULT 查询结果。
        // 前端据此决定表格是否带分页器、是否允许行内编辑。
        String layout = src.get("layout") == null ? "" : src.get("layout").toString();
        out.put("layout", layout);
        out.put("paged", "QUERY_RESULT".equalsIgnoreCase(layout));

        // ── actions：归一化操作点 ──
        // 归一为 { id, name, behaviorRef, primary }，前端直接 v-for 渲染。
        // primary 标记「主操作」（第一个非保存动作），前端据此决定按钮样式。
        List<Map<String, Object>> actions = new ArrayList<>();
        boolean primaryAssigned = false;
        Object actsObj = src.get("actions");
        if (actsObj instanceof List) {
            for (Object a : (List<?>) actsObj) {
                if (!(a instanceof Map)) {
                    continue;
                }
                Map<String, Object> src2 = (Map<String, Object>) a;
                Map<String, Object> item = new HashMap<>();
                item.put("id", src2.get("id"));
                item.put("name", src2.get("name"));
                item.put("behaviorRef", src2.get("behaviorRef"));
                boolean primary = !primaryAssigned
                        && src2.get("behaviorRef") != null
                        && !isSaveAction(src2.get("behaviorRef").toString());
                if (primary) {
                    primaryAssigned = true;
                }
                item.put("primary", primary);
                actions.add(item);
            }
        }
        out.put("actions", actions);

        // ── icon：菜单图标 ──
        // 从菜单树里按 screenRef 反查该界面的图标（MU 声明在 menus[].icon）。
        Object icon = src.get("icon");
        if (icon == null) {
            icon = iconOfScreen(src.get("screenId") == null ? null : src.get("screenId").toString());
        }
        out.put("icon", icon);

        return out;
    }

    /**
     * 判断某个行为是否为「保存」动作（保存是主操作之外的普通操作）。
     *
     * <p>判定依据是行为 ID 的语义后缀——与 M2 的命名约定一致，
     * 不引入额外的模型字段。</p>
     */
    private boolean isSaveAction(String behaviorId) {
        return behaviorId.endsWith("_SaveAsDraft")
                || behaviorId.endsWith("_Save")
                || behaviorId.endsWith("_Create")
                || behaviorId.endsWith("_Update");
    }

    /**
     * 按 screenRef 从菜单树反查图标。
     *
     * <p>MU 把图标声明在 {@code application.menus[].icon}，
     * 界面本身通过 {@code screenRef} 关联——这里把两者接起来。</p>
     */
    @SuppressWarnings("unchecked")
    private Object iconOfScreen(String screenId) {
        if (screenId == null || registry.getMenus() == null) {
            return null;
        }
        for (Object menuObj : registry.getMenus()) {
            if (!(menuObj instanceof Map)) {
                continue;
            }
            Map<String, Object> menu = (Map<String, Object>) menuObj;
            // 一级菜单自身挂了 screenRef
            Object ref = menu.get("screenRef");
            if (ref != null && screenId.equals(ref.toString())) {
                return menu.get("icon");
            }
            // 二级菜单：icon 通常声明在一级菜单上（菜单分组图标），
            // 二级菜单自己不一定有 icon —— 此时继承父级。
            Object parentIcon = menu.get("icon");
            Object children = menu.get("children");
            if (children instanceof List) {
                for (Object c : (List<?>) children) {
                    if (!(c instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> child = (Map<String, Object>) c;
                    Object cref = child.get("screenRef");
                    if (cref != null && screenId.equals(cref.toString())) {
                        Object own = child.get("icon");
                        return own != null ? own : parentIcon;
                    }
                }
            }
        }
        return null;
    }

    /** 菜单树：对应 MU 模型的 application.menus */
    @GetMapping("/menus")
    public R<List<Object>> menus() {
        List<Object> menus = registry.getMenus();
        return R.ok(menus == null ? new ArrayList<>() : menus);
    }

    /** 行为清单：对应 M2 行为模型，前端据此渲染操作按钮 */
    @GetMapping("/behaviors")
    public R<Map<String, Object>> behaviors() {
        Map<String, Object> out = new HashMap<>();
        registry.getBehaviors().forEach((id, b) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", b.getId());
            item.put("name", b.getName());
            item.put("ownerEntity", b.getOwnerEntity());
            item.put("behaviorType", b.getBehaviorType());
            item.put("triggerType", b.getTriggerType());
            item.put("requiredPermissions", b.getRequiredPermissions());
            item.put("appliedRules", b.getAppliedRules());
            // 行为形态：前端据此知道该按钮是模板生成还是手写（决定要不要给二次确认等）
            item.put("behaviorForm", b.getBehaviorForm());
            out.put(id, item);
        });
        return R.ok(out);
    }

    /** 角色与权限清单：对应 M5 主体模型 */
    @GetMapping("/actors")
    public R<Map<String, Object>> actors() {
        Map<String, Object> out = new HashMap<>();
        out.put("roles", registry.getRoles());
        out.put("permissions", registry.getPermissions());
        return R.ok(out);
    }

    /** 模型装载摘要：便于自检与联调时确认模型已正确加载 */
    @GetMapping("/model-summary")
    public R<Map<String, Object>> modelSummary() {
        Map<String, Object> out = new HashMap<>();
        out.put("aggregates", registry.getAggregateNames().size());
        out.put("behaviors", registry.getBehaviors().size());
        out.put("rules", registry.getRules().size());
        out.put("events", registry.getEvents().size());
        out.put("flows", registry.getFlows().size());
        out.put("scenarios", registry.getScenarios().size());
        out.put("roles", registry.getRoles().size());
        out.put("permissions", registry.getPermissions().size());
        out.put("queryReports", registry.getQueryReports().size());
        out.put("screens", registry.getScreens().size());
        out.put("interfaces", registry.getInterfaces().size());
        out.put("mappings", registry.getMappings().size());
        out.put("dataDictionaryItems", registry.getDataDictionaryItems().size());
        return R.ok(out);
    }
}
