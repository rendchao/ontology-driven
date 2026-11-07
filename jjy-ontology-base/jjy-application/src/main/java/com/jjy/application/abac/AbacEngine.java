package com.jjy.application.abac;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;
import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.security.LoginUser;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ABAC（基于属性的访问控制）引擎：执行 M5 的 {@code permissions[].abacCondition}。
 *
 * <p><b>解决的问题</b>：M5 的 {@code abacCondition} 此前<b>只被校验、不被执行</b>——
 * 模型里声明了「这条数据只允许本人看」，运行期却没人求值它，
 * 属性级授权形同虚设。这是典型的「模型说了不算」。</p>
 *
 * <p><b>ABAC 与 RBAC 的分工</b>：</p>
 * <ul>
 *   <li>RBAC（角色 → 权限）：能不能做这个动作；</li>
 *   <li>ABAC（属性 → 条件）：能不能碰这一条数据。</li>
 * </ul>
 * <p>两者都要过：RBAC 放行后，ABAC 再逐条判定。</p>
 *
 * <p><b>表达式语法</b>：与 M3 规则一致（Aviator 5.3）。可用变量：</p>
 * <table border="1">
 *   <caption>ABAC 变量</caption>
 *   <tr><th>变量</th><th>含义</th></tr>
 *   <tr><td>{@code actor.*}</td><td>当前用户属性（userId / username / userType / customerId / roles 等）</td></tr>
 *   <tr><td>{@code row.*}</td><td>目标记录的属性（来自聚合根当前状态）</td></tr>
 *   <tr><td>{@code ctx.*}</td><td>上下文（domain / behaviorId / now）</td></tr>
 * </table>
 * <p>示例：{@code row.ownerId == actor.userId}（仅创建人可改）、
 * {@code row.amount <= actor.limitAmount}（按额度授权）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class AbacEngine {

    /** 模型注册表：读 M5 的 abacCondition 声明 */
    private final ModelRegistry registry;

    private final AviatorEvaluatorInstance evaluator;

    public AbacEngine(ModelRegistry registry) {
        this.registry = registry;
        this.evaluator = AviatorEvaluator.newInstance();
        try {
            this.evaluator.setOption(Options.valueOf("ALLOW_NULL_LITERAL"), true);
        } catch (Exception ignore) {
            // 选项不可用时忽略
        }
        try {
            this.evaluator.setOption(Options.valueOf("ALLOW_NULL_OPERATOR"), true);
        } catch (Exception ignore) {
            // 选项不可用时忽略
        }
    }

    /**
     * 判定当前用户能否访问指定记录。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>从 M5 找该行为对应的权限，取其 {@code abacCondition}；</li>
     *   <li>未声明条件 → 放行（没有 ABAC 约束）；</li>
     *   <li>声明了 → 用 Aviator 求值，变量注入 actor / row / ctx；</li>
     *   <li>结果为 false → 拒绝，抛 {@link BizException}。</li>
     * </ol>
     *
     * @param domain     业务域（聚合根别名，如 Product）
     * @param behaviorId M2 行为 ID（如 Product_Update）
     * @param row        目标记录属性（可为 null——此时 row.* 变量取 null）
     */
    public void check(String domain, String behaviorId, Map<String, Object> row) {
        String cond = abacConditionOf(domain, behaviorId);
        if (cond == null || cond.trim().isEmpty()) {
            return;   // 模型未声明 ABAC 约束 → 不限制
        }
        if (!evaluate(cond, domain, behaviorId, row)) {
            throw new BizException("属性级授权拒绝：当前用户无权访问该数据（条件: " + cond + "）");
        }
    }

    /**
     * 求值 ABAC 条件（不抛异常，供业务自行处理）。
     *
     * @return true=放行；false=拒绝
     */
    public boolean evaluate(String condition, String domain, String behaviorId, Map<String, Object> row) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }
        try {
            Map<String, Object> env = buildEnv(domain, behaviorId, row);
            Expression exp = evaluator.compile(condition, true);
            Object result = exp.execute(env);
            boolean passed = (result instanceof Boolean)
                    ? (Boolean) result
                    : (result != null && Boolean.parseBoolean(String.valueOf(result)));
            if (!passed) {
                log.info("[Abac] 条件不满足: cond={} env={}", condition, env);
            }
            return passed;
        } catch (Exception e) {
            // 求值异常 → 拒绝（fail-closed）。属性级授权宁严勿松。
            log.warn("[Abac] 条件求值失败，按拒绝处理: cond={} err={}", condition, e.getMessage());
            return false;
        }
    }

    /**
     * 从 M5 模型读该行为的 ABAC 条件声明。
     *
     * <p>权限的 {@code targetRef} 指向 M2 行为 ID；按前缀匹配业务域。</p>
     *
     * @param domain     业务域（聚合根别名，如 Product）
     * @param behaviorId M2 行为 ID
     * @return ABAC 条件表达式；未声明返回 null
     */
    @SuppressWarnings("unchecked")
    public String abacConditionOf(String domain, String behaviorId) {
        ModelRegistry reg = registry;
        if (reg == null || domain == null) {
            return null;
        }
        // 精确匹配：权限的 targetRef 就是该行为 ID
        for (Object permObj : reg.getPermissions().values()) {
            if (!(permObj instanceof Map)) {
                continue;
            }
            Map<String, Object> p = (Map<String, Object>) permObj;
            Object targetRef = p.get("targetRef");
            if (targetRef == null || !behaviorId.equals(targetRef.toString())) {
                continue;
            }
            Object cond = p.get("abacCondition");
            return cond == null ? null : cond.toString();
        }
        // 退而求其次：同域的任意权限声明了条件也生效
        String prefix = domain.toLowerCase();
        for (Object permObj : reg.getPermissions().values()) {
            if (!(permObj instanceof Map)) {
                continue;
            }
            Map<String, Object> p = (Map<String, Object>) permObj;
            Object targetRef = p.get("targetRef");
            if (targetRef == null) {
                continue;
            }
            String ref = targetRef.toString();
            int idx = ref.indexOf('_');
            String alias = (idx > 0 ? ref.substring(0, idx) : ref).toLowerCase();
            if (!alias.equals(prefix)) {
                continue;
            }
            Object cond = p.get("abacCondition");
            if (cond != null && !cond.toString().trim().isEmpty()) {
                return cond.toString();
            }
        }
        return null;
    }

    /**
     * 构造求值环境：actor / row / ctx 三个命名空间。
     */
    private Map<String, Object> buildEnv(String domain, String behaviorId, Map<String, Object> row) {
        Map<String, Object> env = new HashMap<>();

        // actor.*：当前用户属性
        Map<String, Object> actor = new HashMap<>();
        LoginUser u = SecurityUtils.currentUser();
        if (u != null) {
            actor.put("userId", u.getUserId());
            actor.put("username", u.getUsername());
            actor.put("userType", u.getUserType());
            actor.put("customerId", u.getCustomerId());
            actor.put("currentTenantId", u.getCurrentTenantId());
            actor.put("roles", u.getRoleIds());
        }
        env.put("actor", actor);

        // row.*：目标记录属性
        Map<String, Object> r = new HashMap<>();
        if (row != null) {
            r.putAll(row);
        }
        env.put("row", r);

        // ctx.*：上下文
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("domain", domain);
        ctx.put("behaviorId", behaviorId);
        env.put("ctx", ctx);

        return env;
    }

    /**
     * 取当前用户的角色 ID 列表（供 ABAC 表达式判断角色）。
     */
    public List<String> actorRoles() {
        LoginUser u = SecurityUtils.currentUser();
        if (u == null || u.getRoleIds() == null) {
            return new java.util.ArrayList<>();
        }
        return new java.util.ArrayList<>(u.getRoleIds());
    }
}
