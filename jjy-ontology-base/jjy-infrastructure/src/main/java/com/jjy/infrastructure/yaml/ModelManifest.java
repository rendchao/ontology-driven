package com.jjy.infrastructure.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * manifest.json 反序列化目标。
 *
 * 支持两种 schema：
 * <ul>
 *   <li>v9 旧契约：model_files 为字符串数组（纯文件名）</li>
 *   <li>方法论 §14.4 新契约：model_files 为对象数组（file + sha256）</li>
 * </ul>
 *
 * 启动时若条目是字符串（无 sha256），视为"未校验"；若是对象则启动器会比对 sha256，不一致仅 WARN 不阻断。
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelManifest {

    private String modelType;
    private String version;
    private String domain;
    private String project;
    private String sourceRequirements;
    private String framework;
    private List<ModelFileEntry> modelFiles = new ArrayList<>();
    private List<String> buildOrder = new ArrayList<>();

    /**
     * 启动期兼容：把 manifest.json 的 model_files 字段（List<Object>）规整成 List<ModelFileEntry>。
     * 字符串条目转成 entry(file=name, sha256=null)。
     * 已是对象的条目直接用。
     */
    @SuppressWarnings("unchecked")
    public static List<ModelFileEntry> normalize(List<Object> rawList) {
        List<ModelFileEntry> out = new ArrayList<>();
        if (rawList == null) {
            return out;
        }
        for (Object o : rawList) {
            if (o instanceof String) {
                out.add(new ModelFileEntry((String) o, null));
            } else if (o instanceof java.util.Map) {
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                String file = (String) m.get("file");
                String sha = m.get("sha256") == null ? null : String.valueOf(m.get("sha256"));
                out.add(new ModelFileEntry(file, sha));
            } else {
                // 兜底：toString
                out.add(new ModelFileEntry(String.valueOf(o), null));
            }
        }
        return out;
    }
}
