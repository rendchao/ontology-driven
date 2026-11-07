package com.jjy.infrastructure.datascope;

/**
 * 数据权限上下文（ThreadLocal）
 *
 * 写入时机：{@link DataScopeAspect} 拦截 @DataScope 注解时
 * 读取时机：Service / Mapper / SQL 拦截器
 *
 * 清理时机：{@link DataScopeAspect} 拦截 finally 中清理，避免线程复用导致污染
 *
 * @author jjy-base
 */
public final class DataScopeContext {

    public enum Scope { ALL, OWN, UNKNOWN }

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<String> CUSTOMER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> DOMAIN = new ThreadLocal<>();

    private DataScopeContext() {}

    public static void set(Scope scope, String customerId, String domain) {
        SCOPE.set(scope);
        CUSTOMER_ID.set(customerId);
        DOMAIN.set(domain);
    }

    public static Scope scope() { return SCOPE.get() == null ? Scope.UNKNOWN : SCOPE.get(); }
    public static String customerId() { return CUSTOMER_ID.get(); }
    public static String domain() { return DOMAIN.get(); }

    public static boolean isAll() { return scope() == Scope.ALL; }
    public static boolean isOwn() { return scope() == Scope.OWN; }

    public static void clear() {
        SCOPE.remove();
        CUSTOMER_ID.remove();
        DOMAIN.remove();
    }
}
