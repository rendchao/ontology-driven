package com.jjy.codegen;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 M1 对象模型 (m1-object-model.yaml)，解析出 aggregate 列表。
 *
 * 不引 Jackson（避免 plugin 跟主项目冲突），用 SnakeYAML 解析为 Map/List。
 * 字段顺序保留（用 LinkedHashMap）。
 */
public final class M1Reader {

    private M1Reader() {}

    /** M1 aggregate 单个属性 */
    public static final class Attr {
        public final String name;
        public final String label;
        public final String type;            // String / Integer / Money / Date / DateTime / Boolean
        public final boolean required;
        public final boolean unique;
        public final boolean pk;              // 第一个 required+unique+name endswith Id 视为主键
        public Attr(String name, String label, String type, boolean required, boolean unique, boolean pk) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.required = required;
            this.unique = unique;
            this.pk = pk;
        }
        @Override
        public String toString() {
            return name + ":" + type + (required ? "*" : "") + (unique ? "!" : "");
        }
    }

    /** M1 aggregate */
    public static final class Aggregate {
        public final String id;          // AGG-XXX-001
        public final String alias;       // Customer / OutboundOrder
        public final String name;        // 客户档案
        public final List<Attr> attributes = new ArrayList<>();
        public Aggregate(String id, String alias, String name) {
            this.id = id;
            this.alias = alias;
            this.name = name;
        }
    }

    /** M1 解析结果 */
    public static final class M1 {
        public final List<Aggregate> aggregates = new ArrayList<>();
        public final List<Aggregate> entities = new ArrayList<>();
    }

    /** 从 yaml 文件读 M1 */
    public static M1 read(Path yamlFile) throws IOException {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) {
                throw new IOException("M1 yaml 根节点不是 Map: " + yamlFile);
            }
            Object aggsObj = ((Map<?, ?>) root).get("aggregates");
            if (!(aggsObj instanceof List)) {
                throw new IOException("M1 yaml 缺 'aggregates' 列表: " + yamlFile);
            }
            M1 m1 = new M1();
            for (Object aObj : (List<?>) aggsObj) {
                if (!(aObj instanceof Map)) continue;
                Map<?, ?> aMap = (Map<?, ?>) aObj;
                String id = str(aMap.get("id"));
                String alias = str(aMap.get("alias"));
                String name = str(aMap.get("name"));
                if (id == null || alias == null) continue;
                Aggregate agg = new Aggregate(id, alias, name);
                Object attrsObj = aMap.get("attributes");
                if (attrsObj instanceof List) {
                    boolean first = true;
                    for (Object atObj : (List<?>) attrsObj) {
                        if (!(atObj instanceof Map)) continue;
                        Map<?, ?> atMap = (Map<?, ?>) atObj;
                        String aName = str(atMap.get("name"));
                        if (aName == null) continue;
                        String aType = str(atMap.get("type"));
                        if (aType == null) aType = "String";
                        String aLabel = str(atMap.get("label"));
                        if (aLabel == null) aLabel = aName;
                        boolean aReq = bool(atMap.get("required"));
                        boolean aUni = bool(atMap.get("unique"));
                        boolean aPk = first && aReq && aUni && aName.endsWith("Id");
                        agg.attributes.add(new Attr(aName, aLabel, aType, aReq, aUni, aPk));
                        first = false;
                    }
                }
                m1.aggregates.add(agg);
                Object children = aMap.get("entities");
                if (children instanceof List) {
                    for (Object child : (List<?>) children) {
                        if (!(child instanceof Map)) continue;
                        Map<?, ?> entity = (Map<?, ?>) child;
                        String entityAlias = str(entity.get("alias"));
                        if (entityAlias == null) throw new IOException("子实体缺少 alias: " + id);
                        Aggregate parsed = new Aggregate(id + "/" + entityAlias, entityAlias, str(entity.get("name")));
                        String localId = str(entity.get("localId"));
                        Object fields = entity.get("attributes");
                        if (fields instanceof List) for (Object field : (List<?>) fields) {
                            Map<?, ?> f = (Map<?, ?>) field;
                            String fieldName = str(f.get("name"));
                            if (fieldName != null) parsed.attributes.add(new Attr(fieldName, str(f.get("label")), str(f.get("type")), bool(f.get("required")), bool(f.get("unique")), fieldName.equals(localId)));
                        }
                        if (localId != null && parsed.attributes.stream().noneMatch(f -> localId.equals(f.name)))
                            parsed.attributes.add(new Attr(localId, localId, "String", true, true, true));
                        m1.entities.add(parsed);
                    }
                }
            }
            return m1;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(o.toString());
    }
}
