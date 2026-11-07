package com.jjy.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成元数据映射表 —— 只保留「工程约定」，物理映射一律以 MM 为准。
 *
 * <p>职责划分：
 * <ul>
 *   <li><b>MM（mm-mapping-model.yaml）</b>：表名、列名、列类型的唯一真源。
 *       由 {@link MmReader} 读入，通过 {@link #setMm(MmReader.Mm)} 注入。
 *       见 {@link #tableName(String)} 和 {@link #columnName(String, String)}。</li>
 *   <li><b>本类</b>：M1 表达不了的工程约定 —— 子包名、是否继承 BaseAuditPO、主键字段。
 *       这些不是业务语义，模型里没有也不该有。</li>
 * </ul>
 *
 * <p>包路径约定：{@code com.jjy.persistence.po.<sub>}
 * —— 与原手写 PO 包名一致，这样 Mapper / Service 的 import 无需改动，
 * 只是文件来源从「手写」变成「模型生成」。</p>
 */
public final class PoMapping {

    private PoMapping() {}

    /** PO 根包名（可由 Mojo 的 poRootPkg 参数覆盖）。 */
    public static final String DEFAULT_PO_ROOT_PKG = "com.jjy.infrastructure.persistence.po";

    private static volatile String poRootPkg = DEFAULT_PO_ROOT_PKG;

    /** alias → 子包名（不含 po. 前缀） */
    private static final Map<String, String> SUB_PACKAGE = new LinkedHashMap<>();
    /** alias → 是否继承 BaseAuditPO */
    private static final Map<String, Boolean> EXTENDS_BASE = new LinkedHashMap<>();
    /** alias → 主键字段名（覆盖「首个 required+unique+*Id」的启发式） */
    private static final Map<String, String> PK_FIELD = new LinkedHashMap<>();

    /** MM 映射（表名/列名的唯一真源）；未注入时为 null */
    private static volatile MmReader.Mm mm;

    /**
     * 设置 PO 根包名（业务项目按自己的包结构指定）。
     */
    public static void setPoRootPkg(String value) {
        if (value != null && !value.trim().isEmpty()) {
            poRootPkg = value.trim();
        }
    }

    /** 当前生效的 PO 根包名。 */
    public static String poRootPkg() {
        return poRootPkg;
    }

    /** 注入 MM（由 Main / CodegenMojo 在读取 yaml 后调用） */
    public static void setMm(MmReader.Mm value) {
        mm = value;
    }

    /** 当前生效的 MM */
    public static MmReader.Mm mm() {
        return mm;
    }

    private static void def(String alias, String sub, boolean base, String pk) {
        SUB_PACKAGE.put(alias, sub);
        EXTENDS_BASE.put(alias, base);
        if (pk != null) PK_FIELD.put(alias, pk);
    }

    static {
        // ── 平台表（底座自带，与业务无关）────────────────────────────────
        def("AuditLog", "", true, "logId");
        def("User",     "", true, "userId");
        def("Role",     "", true, "roleId");

        // ── 业务聚合根 ─────────────────────────────────────────────────
        // 说明：此处不预置任何业务领域映射。新增聚合时在业务项目侧补充，
        // 或依赖「未登记 → alias.toLowerCase()」的默认规则。
    }

    /** 子包名（可能为空串，表示直接在 po 包下） */
    public static String subPackage(String alias) {
        String s = SUB_PACKAGE.get(alias);
        return s == null ? alias.toLowerCase() : s;
    }

    /** 完整包名 */
    public static String packageName(String alias) {
        String sub = subPackage(alias);
        return sub.isEmpty() ? poRootPkg : poRootPkg + "." + sub;
    }

    /**
     * 表名 —— 以 MM 为准。
     * MM 未注入或该实体不在 MM 中时，回退到 {@code t_ + 下划线化 alias}。
     */
    public static String tableName(String alias) {
        if (mm != null) {
            String t = mm.tableNameOf(alias);
            if (t != null) return t;
        }
        return "t_" + PoTemplate.camelToSnake(alias);
    }

    /**
     * 列名 —— 以 MM 为准（MM 未声明该字段时回退到下划线化字段名）。
     */
    public static String columnName(String alias, String field) {
        if (mm != null) {
            MmReader.Column c = mm.columnOf(alias, field);
            if (c != null) return c.columnName;
        }
        return PoTemplate.camelToSnake(field);
    }

    /**
     * 列类型 —— 以 MM 为准（MM 未声明该字段时返回 null，由调用方按 M1 类型推导）。
     */
    public static String columnType(String alias, String field) {
        if (mm != null) {
            MmReader.Column c = mm.columnOf(alias, field);
            if (c != null) return c.columnType;
        }
        return null;
    }

    /** 是否继承 BaseAuditPO */
    public static boolean extendsBase(String alias) {
        return EXTENDS_BASE.getOrDefault(alias, Boolean.TRUE);
    }

    /** 主键字段名；未登记时返回 null（由模板按启发式判断） */
    public static String pkField(String alias) {
        return PK_FIELD.get(alias);
    }

    /**
     * 主键字段解析：优先用显式登记的工程约定，否则**从 M1 模型推导**。
     *
     * <p>推导规则：实体中<b>有且仅有一个</b>同时满足
     * {@code required: true} + {@code unique: true} + <b>字段名以 {@code Id} 结尾</b>的字段。</p>
     *
     * <p>「以 Id 结尾」这条约束是必要的：像「商品编号」这类字段往往也是
     * required + unique，若不排除，一个实体里会有多个候选、无法判定主键。</p>
     *
     * <p>为什么必须推导而不是硬编码：主键是<b>业务语义</b>，属于模型；
     * 若在 Java 的 {@code PoMapping} 里再写一遍，就成了「模型一套、代码一套」——
     * 模型改了主键而代码没改，生成的 DDL 与 PO 会不一致，
     * 且 MySQL 会因「PRIMARY KEY 列必须 NOT NULL」直接建表失败。</p>
     *
     * @param alias 实体别名
     * @param attrs 该实体的 M1 属性列表（可为空，此时不做推导）
     * @return 主键字段名；无法确定时返回 null
     */
    public static String resolvePkField(String alias, List<M1Reader.Attr> attrs) {
        String explicit = PK_FIELD.get(alias);
        if (explicit != null) {
            return explicit;
        }
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        String candidate = null;
        for (M1Reader.Attr a : attrs) {
            if (a == null || !a.required || !a.unique) {
                continue;
            }
            // M1Reader 已按 localId 规则标记过 pk，优先采纳
            if (a.pk) {
                return a.name;
            }
            if (!a.name.endsWith("Id")) {
                continue;
            }
            if (candidate != null) {
                // 不唯一符合，无法判定
                return null;
            }
            candidate = a.name;
        }
        return candidate;
    }

    /**
     * 主键 IdType —— ASSIGN_ID（UUID）/ AUTO（自增）/ INPUT（业务组合键）。
     *
     * <p>判定依据是 MM 里主键列的实际 SQL 类型：
     * <ul>
     *   <li>{@code bigint} → AUTO（DB 自增）</li>
     *   <li>其它 → ASSIGN_ID（由业务侧生成 UUID）</li>
     * </ul>
     * 组合键（如 InventoryMirror 的「客户+SKU+批次+库位」）必须由业务方拼装，
     * 用 INPUT —— 否则 MyBatis-Plus 会往里塞雪花 ID。</p>
     */
    public static String pkIdType(String alias) {
        if (COMPOSITE_PK.contains(alias)) return "INPUT";
        if (mm != null) {
            String pkField = PK_FIELD.get(alias);
            if (pkField != null) {
                MmReader.Column c = mm.columnOf(alias, pkField);
                if (c != null && c.columnType != null
                        && c.columnType.toLowerCase().startsWith("bigint")) {
                    return "AUTO";
                }
            }
        }
        return "ASSIGN_ID";
    }

    /**
     * 业务组合键的主键（主键值由业务方拼装，不能自动生成）。
     * 底座不预置任何业务领域，由业务项目侧登记。
     */
    private static final java.util.Set<String> COMPOSITE_PK = new java.util.HashSet<>();

    /**
     * 登记组合键聚合（主键由业务方拼装，IdType 用 INPUT）。
     */
    public static void registerCompositePk(String alias) {
        if (alias != null && !alias.trim().isEmpty()) {
            COMPOSITE_PK.add(alias.trim());
        }
    }
}
