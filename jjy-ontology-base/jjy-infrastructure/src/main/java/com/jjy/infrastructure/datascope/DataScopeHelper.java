package com.jjy.infrastructure.datascope;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.security.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据权限判定 helper。
 *
 * <p>业务代码可用静态方法做权限分支；亦可改用 {@link DataScope} 注解 + {@link DataScopeAspect}。
 *
 * <h3>数据范围来自 M5 模型，不是代码里的约定</h3>
 * <p>M5 的 {@code permissions[].dataScope} 声明了每个权限的数据范围
 * （ALL / OWN / DEPT / CUSTOM）。本类<b>读模型</b>来判定，
 * 而不是按 {@code <domain>:query-all} 这种权限码命名约定去猜——
 * 后者一旦权限码改名就会静默降级，而模型才是唯一语义源。</p>
 *
 * <p>判定顺序：</p>
 * <ol>
 *   <li>用户持有通配符 {@code *} → ALL</li>
 *   <li>用户是 PLATFORM 平台账户 → ALL（平台视角天然看全）</li>
 *   <li>从 M5 查该域对应权限的 {@code dataScope} 声明：
 *       {@code ALL} → ALL；{@code OWN} → OWN</li>
 *   <li>M5 未声明 dataScope → 回退权限码约定（兼容旧模型）</li>
 * </ol>
 *
 * @author jjy-base
 */
@Component
public final class DataScopeHelper {

    /**
     * 模型注册表：用于读 M5 的 dataScope 声明。
     *
     * <p>由 Spring 构造器注入。模型未装载时（registry 为 null 或权限未声明）
     * 退化为纯权限码约定，不影响可用性。</p>
     */
    private final ModelRegistry registry;

    public DataScopeHelper(ModelRegistry registry) {
        this.registry = registry;
    }

    /** 是否"看全部"（PLATFORM / 超管 / M5 声明 dataScope=ALL） */
    public boolean isAll(String domain) {
        if (!SecurityUtils.isAuthenticated()) return false;
        // 通配最优先
        if (SecurityUtils.hasPermission("*")) return true;
        // PLATFORM 用户默认看全部
        if ("PLATFORM".equals(SecurityUtils.currentUserType())) return true;

        String scope = dataScopeOf(domain);
        if ("ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        if ("OWN".equalsIgnoreCase(scope)) {
            return false;
        }
        // 模型未声明 → 回退权限码约定
        return SecurityUtils.hasPermission(domain + ":query-all");
    }

    /** 是否"只看本租户" */
    public boolean isOwn(String domain) {
        if (!SecurityUtils.isAuthenticated()) return false;
        if (isAll(domain)) return false; // ALL 优先

        String scope = dataScopeOf(domain);
        if ("OWN".equalsIgnoreCase(scope)) {
            return true;
        }
        // 模型未声明 → 回退权限码约定
        return SecurityUtils.hasPermission(domain + ":query-own")
                || SecurityUtils.hasAnyPermission(domain + ":query-detail");
    }

    /**
     * 从 M5 模型读该域的数据范围声明。
     *
     * <p>权限的 {@code targetRef} 指向 M2 行为 ID（如 {@code Product_Query}），
     * 行为 ID 的聚合前缀即业务域（如 {@code Product} → {@code product}）。</p>
     *
     * @param domain 业务域（如 product）
     * @return {@code ALL} / {@code OWN} / {@code DEPT} / {@code CUSTOM}；无声明返回 null
     */
    public String dataScopeOf(String domain) {
        ModelRegistry reg = registry;
        if (reg == null || domain == null) {
            return null;
        }
        String prefix = domain.toLowerCase();
        for (Object permObj : reg.getPermissions().values()) {
            if (!(permObj instanceof Map)) {
                continue;
            }
            Map<?, ?> p = (Map<?, ?>) permObj;
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
            Object scope = p.get("dataScope");
            return scope == null ? null : scope.toString();
        }
        return null;
    }

    /** 当前客户 id（可能为 null —— 当 isAll=true 时按业务决定是否需要） */
    public String currentCustomerId() {
        return SecurityUtils.currentCustomerId();
    }

    /**
     * 是否平台账户（user_type=PLATFORM），平台看全平台不需要选租户
     */
    public boolean isPlatform() {
        if (!SecurityUtils.isAuthenticated()) return false;
        return "PLATFORM".equals(SecurityUtils.currentUserType());
    }
}
