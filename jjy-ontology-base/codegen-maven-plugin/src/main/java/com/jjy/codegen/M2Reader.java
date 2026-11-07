package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 读 M2 行为模型 (m2-behavior-model.yaml)，解析出 behavior 列表。
 *
 * <p>供模板生成器（{@link HandlerGenerator}）使用：M2 声明
 * {@code behaviorForm=TEMPLATE} 的行为，由生成器产出 Handler 骨架。</p>
 *
 * <p><b>注意</b>：本类原先从 Python 底座移植而来，有三处与 Java 现状不符，已修正：</p>
 * <ol>
 *   <li>{@code postconditions} 是<b>状态变更对象列表</b>（{@code field/setValue}），
 *       不是字符串列表——原先按 {@code List<String>} 读，状态语义整个丢失；</li>
 *   <li>{@code ownerEntity} 在 Java 底座里是<b>聚合根别名</b>（如 {@code Product}），
 *       不是 {@code AGG-XXX-001}——原先按 ID 解析会得到错误的子包名；</li>
 *   <li>新增 {@code behaviorForm}——「本行为由谁实现」的判据，
 *       没有它生成器不知道该为谁生成。</li>
 * </ol>
 */
public final class M2Reader {

    private M2Reader() {}

    /** 行为形态：模板生成 / 手写 / 脚本 / 纯声明 */
    public static final class Form {
        public static final String TEMPLATE = "TEMPLATE";
        public static final String HANDWRITTEN = "HANDWRITTEN";
        public static final String SCRIPT = "SCRIPT";
        public static final String DECLARATIVE = "DECLARATIVE";

        private Form() {
        }
    }

    /** M2 behavior 单个条目 */
    public static final class Behavior {
        public final String id;
        public final String name;
        public final String ownerEntity;
        public final String behaviorType;     // COMMAND / QUERY
        public final String triggerType;      // USER_ACTION / EVENT / SYSTEM
        public final String behaviorForm;     // TEMPLATE / HANDWRITTEN / SCRIPT / DECLARATIVE
        public final String templateKind;     // 模板形态：DELETE / UPDATE / BATCH_DELETE / QUERY_PAGE
        public final String description;
        public final String queryReportRef;
        public final List<String> preconditions;
        /** 后置状态变更：每项含 field / setValue（可能还有 via） */
        public final List<Map<String, String>> postconditions;
        public final List<String> appliedRules;
        public final List<String> requiredPermissions;
        public final List<String> producedEvents;
        public final List<String> subscribedEvents;

        public Behavior(String id, String name, String ownerEntity, String behaviorType,
                        String triggerType, String behaviorForm, String templateKind,
                        String description,
                        String queryReportRef, List<String> pre,
                        List<Map<String, String>> post, List<String> rules,
                        List<String> perms, List<String> produced, List<String> subscribed) {
            this.id = id; this.name = name; this.ownerEntity = ownerEntity;
            this.behaviorType = behaviorType; this.triggerType = triggerType;
            this.behaviorForm = behaviorForm; this.templateKind = templateKind;
            this.description = description;
            this.queryReportRef = queryReportRef;
            this.preconditions = pre; this.postconditions = post;
            this.appliedRules = rules; this.requiredPermissions = perms;
            this.producedEvents = produced; this.subscribedEvents = subscribed;
        }

        /** 是否为模板生成的行为 */
        public boolean isTemplate() {
            return Form.TEMPLATE.equalsIgnoreCase(behaviorForm);
        }

        /** 聚合根别名（Java 底座的 ownerEntity 就是别名，如 Product） */
        public String alias() {
            if (ownerEntity == null || ownerEntity.isEmpty()) {
                return "Unknown";
            }
            String s = ownerEntity;
            if (s.startsWith("AGG-")) {
                String mid = s.substring(4);
                int dash = mid.lastIndexOf('-');
                if (dash > 0) {
                    mid = mid.substring(0, dash);
                }
                StringBuilder sb = new StringBuilder();
                boolean up = true;
                for (int i = 0; i < mid.length(); i++) {
                    char c = mid.charAt(i);
                    if (c == '-' || c == '_') {
                        up = true;
                        continue;
                    }
                    sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                    up = false;
                }
                s = sb.toString();
            }
            return s;
        }
    }

    public static final class M2 {
        public final List<Behavior> behaviors;

        public M2(List<Behavior> b) {
            this.behaviors = b;
        }

        /** 取所有声明为模板生成的行为 */
        public List<Behavior> templateBehaviors() {
            List<Behavior> out = new ArrayList<>();
            for (Behavior b : behaviors) {
                if (b.isTemplate()) {
                    out.add(b);
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    public static M2 read(Path m2Yaml) throws IOException {
        List<Behavior> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(m2Yaml)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) {
                return new M2(out);
            }
            Object arr = ((Map<?, ?>) root).get("behaviors");
            if (!(arr instanceof List)) {
                return new M2(out);
            }
            for (Object o : (List<?>) arr) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> b = (Map<String, Object>) o;
                out.add(new Behavior(
                        str(b, "id"), str(b, "name"), str(b, "ownerEntity"),
                        str(b, "behaviorType"), str(b, "triggerType"),
                        form(b), templateKind(b), str(b, "description"), str(b, "queryReportRef"),
                        strList(b, "preconditions"), mapList(b, "postconditions"),
                        strList(b, "appliedRules"), strList(b, "requiredPermissions"),
                        strList(b, "producedEvents"), strList(b, "subscribedEvents")));
            }
        }
        return new M2(out);
    }

    /**
     * 取行为形态；未声明时按 HANDWRITTEN 处理（向后兼容）。
     */
    private static String form(Map<String, Object> m) {
        String v = str(m, "behaviorForm");
        return v == null || v.isEmpty() ? Form.HANDWRITTEN : v;
    }

    /**
     * 取模板形态；未声明时按 DELETE 处理（向后兼容）。
     *
     * <p>模板形态决定生成哪种 Handler 骨架：</p>
     * <ul>
     *   <li>{@code DELETE} —— 逻辑删除（单条）</li>
     *   <li>{@code UPDATE} —— 按主键更新可变字段</li>
     *   <li>{@code BATCH_DELETE} —— 按条件批量逻辑删除</li>
     *   <li>{@code QUERY_PAGE} —— 分页查询（走 M7 报表口径）</li>
     * </ul>
     */
    private static String templateKind(Map<String, Object> m) {
        String v = str(m, "templateKind");
        return v == null || v.isEmpty() ? TemplateKind.DELETE : v;
    }

    /** 模板形态常量 */
    public static final class TemplateKind {
        public static final String DELETE = "DELETE";
        public static final String UPDATE = "UPDATE";
        public static final String BATCH_DELETE = "BATCH_DELETE";
        public static final String QUERY_PAGE = "QUERY_PAGE";

        private TemplateKind() {
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            return "";
        }
        return v.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List) {
            List<String> r = new ArrayList<>();
            for (Object x : (List<?>) v) {
                if (x != null) {
                    r.add(x.toString().trim());
                }
            }
            return r;
        }
        return Collections.emptyList();
    }

    /**
     * 读对象列表（每项是 Map），用于 postconditions（field / setValue / via）。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> mapList(Map<String, Object> m, String k) {
        List<Map<String, String>> r = new ArrayList<>();
        Object v = m.get(k);
        if (v instanceof List) {
            for (Object x : (List<?>) v) {
                if (!(x instanceof Map)) {
                    continue;
                }
                Map<String, String> item = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) x).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        item.put(e.getKey().toString(), e.getValue().toString());
                    }
                }
                r.add(item);
            }
        }
        return r;
    }
}
