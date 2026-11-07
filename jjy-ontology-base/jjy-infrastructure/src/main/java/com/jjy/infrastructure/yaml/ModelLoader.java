package com.jjy.infrastructure.yaml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jjy.domain.behavior.Behavior;
import com.jjy.domain.event.DomainEvent;
import com.jjy.domain.flow.Flow;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.domain.rule.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型加载器
 * <p>
 * 启动时从 classpath:models/ 读 11 个 yaml + manifest.json，填充 ModelRegistry。
 * <p>
 * 路径约定：模型唯一地址为 {@code jjy-base/models/}，
 * 由 jjy-boot/pom.xml 的第 2 个 resource 源同步到 {@code target/classes/models/}，
 * 运行期从 {@code classpath:models/} 读取。
 * <p>
 * manifest.json 支持两种 schema：
 * <ul>
 *   <li>旧契约：model_files 为字符串数组</li>
 *   <li>新契约：model_files 为对象数组（file + sha256），启动期校验</li>
 * </ul>
 * sha256 不一致仅 WARN，不阻断启动（按方法论 §14.4）。
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ModelLoader {

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private ModelRegistry registry;

    /**
     * Spring 事件发布器：加载完成后发布 {@link ModelLoadedEvent}。
     *
     * <p>用 {@code ObjectProvider} 延迟获取，避免基础设施层在容器早期
     * 构造时拿到未就绪的发布器实例。</p>
     */
    @Autowired(required = false)
    private ObjectProvider<ApplicationEventPublisher> applicationEventPublisherProvider;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    {
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        yamlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    /** manifest 中登记的 sha256（按文件名） */
    private Map<String, String> expectedSha256 = new HashMap<>();

    /**
     * 应用启动后加载所有模型
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            log.info("[ModelLoader] 开始加载本体模型...");
            loadManifest();
            loadM1();
            loadM2();
            loadM3();
            loadM4();
            loadM5();
            loadM6();
            loadM7();
            loadME();
            loadMI();
            loadMM();
            loadMU();
            registry.logSummary();
            if (!registry.validateReferences()) {
                throw new IllegalStateException("本体模型跨模型引用校验失败，请查看 validate 错误日志");
            }
            log.info("[ModelLoader] 加载完成");
            // 发布「模型已装载」事件：依赖注册表的启动期组件据此初始化，
            // 避免依赖 ApplicationReadyEvent 监听器的执行顺序（不确定）。
            publishModelLoadedEvent();
        } catch (Exception e) {
            log.error("[ModelLoader] 加载失败", e);
            throw new RuntimeException("模型加载失败", e);
        }
    }

    /**
     * 发布「模型已装载完成」事件。
     *
     * <p>用 {@code ObjectProvider} 取发布器，避免基础设施层强依赖 Spring 容器初始化顺序
     * （若直接注入 ApplicationEventPublisher，在极早期构造时可能拿到代理未就绪的实例）。</p>
     */
    private void publishModelLoadedEvent() {
        try {
            if (applicationEventPublisherProvider == null) {
                log.warn("[ModelLoader] 未注入 ApplicationEventPublisher，跳过发布 ModelLoadedEvent");
                return;
            }
            ApplicationEventPublisher publisher = applicationEventPublisherProvider.getIfAvailable();
            if (publisher == null) {
                log.warn("[ModelLoader] 容器内无 ApplicationEventPublisher，跳过发布 ModelLoadedEvent");
                return;
            }
            ModelLoadedEvent evt = new ModelLoadedEvent(
                    registry.getAggregateNames().size(),
                    registry.getBehaviors().size(),
                    registry.getEvents().size());
            publisher.publishEvent(evt);
            log.info("[ModelLoader] 已发布 {}", evt);
        } catch (Exception e) {
            log.warn("[ModelLoader] 发布 ModelLoadedEvent 失败: {}", e.getMessage());
        }
    }

    private void loadManifest() throws Exception {
        Resource res = resourceLoader.getResource("classpath:models/manifest.json");
        if (!res.exists()) {
            log.warn("[ModelLoader] manifest.json 不存在");
            return;
        }
        try (InputStream in = res.getInputStream()) {
            Map<String, Object> raw = yamlMapper.readValue(in, Map.class);
            log.info("[ModelLoader] manifest: domain={}, project={}, version={}",
                    raw.get("domain"), raw.get("project"), raw.get("version"));

            // 兼容两种 schema：字符串数组（旧）或对象数组（新）
            Object mfObj = raw.get("model_files");
            if (mfObj instanceof List) {
                List<ModelFileEntry> entries = ModelManifest.normalize((List<Object>) mfObj);
                int withHash = 0;
                for (ModelFileEntry e : entries) {
                    expectedSha256.put(e.getFile(), e.getSha256());
                    if (e.getSha256() != null && !e.getSha256().isEmpty()) {
                        withHash++;
                    }
                }
                if (withHash == 0) {
                    log.warn("[ModelLoader] manifest.json model_files 未登记 sha256，" +
                            "建议运行 scripts/regenerate-manifest.ps1 重算");
                } else {
                    log.info("[ModelLoader] manifest.json 已登记 {}/{} 个 sha256", withHash, entries.size());
                }
            }
        }
    }

    /**
     * 加载 M1 的数据字典。
     *
     * <p>字典是前端下拉框（Enum / DictionaryRef 控件）的唯一数据源，
     * 由模型声明后自动生效，<b>无需在数据库里另建字典表</b>。</p>
     *
     * <p>注册表键为「字典ID + typeCode + code」，值里保留 typeCode 与 label，
     * 供 {@code /meta/dictionaries} 直接返回给前端。</p>
     */
    private void loadDataDictionaries(Map<String, Object> root) {
        List<Map<String, Object>> dicts =
                (List<Map<String, Object>>) root.get("dataDictionaries");
        if (dicts == null) {
            return;
        }
        for (Map<String, Object> dict : dicts) {
            String dictId = firstNonNull((String) dict.get("id"), (String) dict.get("dictionaryId"));
            List<Map<String, Object>> types = (List<Map<String, Object>>) dict.get("types");
            if (types == null) {
                continue;
            }
            for (Map<String, Object> type : types) {
                String typeCode = (String) type.get("typeCode");
                List<Map<String, Object>> items = (List<Map<String, Object>>) type.get("items");
                if (items == null) {
                    continue;
                }
                for (Map<String, Object> item : items) {
                    String code = (String) item.get("code");
                    if (code == null) {
                        continue;
                    }
                    Map<String, Object> value = new HashMap<>();
                    value.put("dictionaryId", dictId);
                    value.put("typeCode", typeCode);
                    value.put("code", code);
                    value.put("label", item.get("label"));
                    value.put("enabled", item.get("enabled"));
                    value.put("sortOrder", item.get("sortOrder"));
                    registry.getDataDictionaryItems().put(dictId + ":" + typeCode + ":" + code, value);
                }
            }
        }
        log.info("[ModelLoader] 数据字典项: {}", registry.getDataDictionaryItems().size());
    }

    private void loadM1() throws Exception {
        Map<String, Object> root = loadYaml("m1-object-model.yaml");
        List<Map<String, Object>> aggregates = (List<Map<String, Object>>) root.get("aggregates");
        if (aggregates != null) {
            for (Map<String, Object> agg : aggregates) {
                String aggId = firstNonNull((String) agg.get("id"), (String) agg.get("aggregateId"));
                if (aggId == null) {
                    log.warn("[ModelLoader] m1-object-model.yaml 中存在缺少 id 的聚合根，已跳过");
                    continue;
                }
                registry.getAggregateNames().put(aggId, (String) agg.get("name"));
                // 保留完整声明：运行期需要 alias、编号规则等细节（模型是唯一语义来源）
                registry.getAggregateDefs().put(aggId, agg);
                // 聚合根 ID → 模型别名（M2.ownerEntity / M3.sourceField 用别名引用）
                String alias = (String) agg.get("alias");
                if (alias != null && !alias.trim().isEmpty()) {
                    registry.getAggregateAliases().put(aggId, alias);
                }
            }
        }
        // 数据字典与聚合根同在 M1，一并加载（前端下拉框的唯一数据源）
        loadDataDictionaries(root);
    }

    private void loadM2() throws Exception {
        Map<String, Object> root = loadYaml("m2-behavior-model.yaml");
        List<Map<String, Object>> behaviors = (List<Map<String, Object>>) root.get("behaviors");
        if (behaviors != null) {
            for (Map<String, Object> m : behaviors) {
                Behavior b = yamlMapper.convertValue(m, Behavior.class);
                registry.getBehaviors().put(b.getId(), b);
            }
        }
    }

    private void loadM3() throws Exception {
        Map<String, Object> root = loadYaml("m3-rule-model.yaml");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) root.get("rules");
        if (rules != null) {
            for (Map<String, Object> m : rules) {
                Rule r = yamlMapper.convertValue(m, Rule.class);
                registry.getRules().put(r.getId(), r);
            }
        }
    }

    private void loadM4() throws Exception {
        Map<String, Object> root = loadYaml("m4-scenario-model.yaml");
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) root.get("scenarios");
        if (scenarios != null) {
            for (Map<String, Object> s : scenarios) {
                // 兼容 scenarioId / id 两种写法
                String scenarioId = firstNonNull((String) s.get("scenarioId"), (String) s.get("id"));
                if (scenarioId == null) {
                    log.warn("[ModelLoader] m4-scenario-model.yaml 中存在缺少 scenarioId/id 的场景，已跳过");
                    continue;
                }
                registry.getScenarios().put(scenarioId, s);
            }
        }
    }

    /**
     * 取第一个非 null 的字符串值。
     *
     * <p>模型的 ID 字段在不同 YAML 里写法不统一（{@code id} / {@code xxxId}），
     * 统一用本方法兜底，避免注册表键为 null 导致元素"静默丢失"。</p>
     */
    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private void loadM5() throws Exception {
        Map<String, Object> root = loadYaml("m5-actor-model.yaml");
        List<Map<String, Object>> roles = (List<Map<String, Object>>) root.get("roles");
        if (roles != null) {
            for (Map<String, Object> r : roles) {
                String roleId = firstNonNull((String) r.get("roleId"), (String) r.get("id"));
                if (roleId == null) {
                    log.warn("[ModelLoader] m5-actor-model.yaml 中存在缺少 roleId 的角色，已跳过");
                    continue;
                }
                registry.getRoles().put(roleId, r);
            }
        }
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) root.get("permissions");
        if (permissions != null) {
            for (Map<String, Object> p : permissions) {
                String permId = firstNonNull((String) p.get("permissionId"), (String) p.get("id"));
                if (permId == null) {
                    log.warn("[ModelLoader] m5-actor-model.yaml 中存在缺少 permissionId 的权限，已跳过");
                    continue;
                }
                registry.getPermissions().put(permId, p);
            }
        }
        List<Map<String, Object>> exts = (List<Map<String, Object>>) root.get("external_entities");
        if (exts != null) {
            for (Map<String, Object> e : exts) {
                String extId = firstNonNull((String) e.get("externalId"), (String) e.get("id"));
                if (extId == null) {
                    log.warn("[ModelLoader] m5-actor-model.yaml 中存在缺少 externalId 的外部实体，已跳过");
                    continue;
                }
                registry.getExternalEntities().put(extId, e);
            }
        }
    }

    private void loadM6() throws Exception {
        Map<String, Object> root = loadYaml("m6-flow-model.yaml");
        List<Map<String, Object>> flows = (List<Map<String, Object>>) root.get("flows");
        if (flows != null) {
            for (Map<String, Object> m : flows) {
                Flow f = yamlMapper.convertValue(m, Flow.class);
                String flowId = firstNonNull(f.getId(), (String) m.get("flowId"));
                if (flowId == null) {
                    log.warn("[ModelLoader] m6-flow-model.yaml 中存在缺少 id 的流程，已跳过");
                    continue;
                }
                registry.getFlows().put(flowId, f);
            }
        }
    }

    private void loadM7() throws Exception {
        Map<String, Object> root = loadYaml("m7-report-model.yaml");
        List<Map<String, Object>> reports = (List<Map<String, Object>>) root.get("query_reports");
        if (reports != null) {
            for (Map<String, Object> r : reports) {
                String reportId = firstNonNull((String) r.get("id"), (String) r.get("queryReportId"));
                if (reportId == null) {
                    log.warn("[ModelLoader] m7-report-model.yaml 中存在缺少 id 的报表，已跳过");
                    continue;
                }
                registry.getQueryReports().put(reportId, r);
            }
        }
    }

    private void loadME() throws Exception {
        Map<String, Object> root = loadYaml("me-event-model.yaml");
        List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
        if (events != null) {
            for (Map<String, Object> m : events) {
                normalizeEventFields(m);
                DomainEvent e = yamlMapper.convertValue(m, DomainEvent.class);
                String eventId = firstNonNull(e.getEventId(), (String) m.get("id"));
                if (eventId == null) {
                    log.warn("[ModelLoader] me-event-model.yaml 中存在缺少 eventId/id 的事件，已跳过");
                    continue;
                }
                registry.getEvents().put(eventId, e);
            }
        }
    }

    /**
     * 归一化 ME 模型的事件字段名。
     *
     * <p>模型里事件字段存在两套写法，而 {@link DomainEvent} POJO 只认其中一套，
     * 不做归一化会导致 <b>事件 payload 解析为空</b>——事件照发，但下游拿不到任何业务字段，
     * 表现为「事件产出正常、消费方收不到数据」这种极难排查的静默缺陷：</p>
     *
     * <table border="1">
     *   <caption>字段名对照</caption>
     *   <tr><th>模型写法</th><th>POJO 字段</th></tr>
     *   <tr><td>{@code id}</td><td>{@code eventId}</td></tr>
     *   <tr><td>{@code aggregateRef}</td><td>{@code sourceAggregate}</td></tr>
     *   <tr><td>{@code payloadFields}</td><td>{@code payload}</td></tr>
     * </table>
     *
     * @param m 事件声明（原地修改）
     */
    @SuppressWarnings("unchecked")
    private void normalizeEventFields(Map<String, Object> m) {
        copyIfAbsent(m, "eventId", "id");
        copyIfAbsent(m, "sourceAggregate", "aggregateRef");
        copyIfAbsent(m, "payload", "payloadFields");
    }

    /** 若目标键不存在而源键存在，则复制一份到目标键。 */
    private void copyIfAbsent(Map<String, Object> m, String target, String source) {
        if (m == null || m.containsKey(target) || !m.containsKey(source)) {
            return;
        }
        m.put(target, m.get(source));
    }

    private void loadMI() throws Exception {
        Map<String, Object> root = loadYaml("mi-interface-model.yaml");
        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) root.get("interfaces");
        if (interfaces != null) {
            for (Map<String, Object> i : interfaces) {
                String intfId = firstNonNull((String) i.get("interfaceId"), (String) i.get("id"));
                if (intfId == null) {
                    log.warn("[ModelLoader] mi-interface-model.yaml 中存在缺少 interfaceId 的接口，已跳过");
                    continue;
                }
                registry.getInterfaces().put(intfId, i);
            }
        }
    }

    private void loadMM() throws Exception {
        Map<String, Object> root = loadYaml("mm-mapping-model.yaml");

        // 默认列（顶层字段，不在 mappings 数组内）
        // 此前完全没被装载 —— MM 声明的默认列是死声明。
        Object defCols = root.get("default_columns");
        if (defCols instanceof List) {
            for (Object c : (List<?>) defCols) {
                if (c != null) {
                    registry.getDefaultColumns().add(c.toString().trim());
                }
            }
            log.info("[ModelLoader] MM 默认列: " + registry.getDefaultColumns());
        }

        List<Map<String, Object>> mappings = (List<Map<String, Object>>) root.get("mappings");
        if (mappings != null) {
            for (Map<String, Object> m : mappings) {
                String mappingId = firstNonNull((String) m.get("mappingId"), (String) m.get("id"));
                if (mappingId == null) {
                    log.warn("[ModelLoader] mm-mapping-model.yaml 中存在缺少 mappingId 的映射，已跳过");
                    continue;
                }
                registry.getMappings().put(mappingId, m);
            }
        }
    }

    private void loadMU() throws Exception {
        Map<String, Object> root = loadYaml("mu-ui-model.yaml");
        Map<String, Object> app = (Map<String, Object>) root.get("application");
        if (app != null) {
            registry.setMenus((List<Object>) app.get("menus"));
        }
        List<Map<String, Object>> screens = (List<Map<String, Object>>) root.get("screens");
        if (screens != null) {
            for (Map<String, Object> s : screens) {
                String screenId = firstNonNull((String) s.get("screenId"), (String) s.get("id"));
                if (screenId == null) {
                    log.warn("[ModelLoader] mu-ui-model.yaml 中存在缺少 screenId 的界面，已跳过");
                    continue;
                }
                registry.getScreens().put(screenId, s);
            }
        }
    }

    private Map<String, Object> loadYaml(String filename) throws Exception {
        Resource res = resourceLoader.getResource("classpath:models/" + filename);
        if (!res.exists()) {
            log.error("[ModelLoader] 找不到模型文件: {}", filename);
            throw new RuntimeException("模型文件缺失: " + filename);
        }
        try (InputStream in = res.getInputStream()) {
            // 1. 计算 SHA-256，与 manifest 登记值比对
            String actual = Sha256Util.digest(in);
            String expected = expectedSha256.get(filename);
            if (!Sha256Util.verify(expected, actual)) {
                log.warn("[ModelLoader] {} SHA-256 不匹配：manifest={} actual={}（模型被修改？或忘了 regenerate-manifest）",
                        filename, expected, actual);
            }

            // 2. 重新打开 InputStream 解析（digest 已读完流）
            try (InputStream in2 = res.getInputStream()) {
                return yamlMapper.readValue(in2, Map.class);
            }
        }
    }
}
