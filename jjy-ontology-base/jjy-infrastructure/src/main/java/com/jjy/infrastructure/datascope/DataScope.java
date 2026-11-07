package com.jjy.infrastructure.datascope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限声明：标记 Service 方法需要按数据域（资源名）做权限收敛
 *
 * 配合 {@link DataScopeAspect} 使用，效果：
 *   - 把当前线程的 DataScopeContext 设好
 *   - 校验调用方至少有 domain 命名空间下的一种权限（query-all/query-own/...）
 *   - 业务代码内可通过 {@link DataScopeHelper#isAll(String)} / {@link DataScopeHelper#isOwn(String)} 决定走全量还是租户过滤
 *
 * 用法：
 *   <pre>
 *   {@code @DataScope("inbound")}
 *   public List<OrderPO> listOrder(...) { ... }
 *   </pre>
 *
 * 也支持显式指定权限（用于 resource 名 ≠ permission 前缀的场景）：
 *   {@code @DataScope(value="inbound", allPerm="inbound:query-all", ownPerm="inbound:query-own")}
 *
 * @author jjy-base
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
    /** 资源域：与权限前缀保持一致，如 inbound/outbound/customer/statement ... */
    String value();

    /** 看全部需要的权限；默认 <value>:query-all */
    String allPerm() default "";

    /** 看本租户需要的权限；默认 <value>:query-own */
    String ownPerm() default "";
}
