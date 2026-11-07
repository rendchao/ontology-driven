package com.jjy.infrastructure.datascope;

import com.jjy.infrastructure.datascope.DataScopeContext.Scope;
import com.jjy.infrastructure.exception.BizException;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 数据权限切面：拦截 {@link DataScope} 注解的方法
 *
 * 行为：
 *   1) 读取当前用户 → 决定 ALL / OWN
 *   2) 把决策写进 {@link DataScopeContext}（ThreadLocal）
 *   3) 校验：用户必须至少有 query-all 或 query-own 之一
 *   4) finally 清 ThreadLocal
 *
 * 注意：业务代码若要"按域分支走不同 mapper"，应继续使用 {@link DataScopeHelper}。
 * Aspect 这里只负责"决策 + 上下文注入 + 最小权限校验"。
 *
 * @author jjy-base
 */
@Slf4j
@Aspect
@Component
@Order(0)  // 早于 @Auditable（audit 默认在更高 order）
public class DataScopeAspect {

    /**
     * 数据范围判定器：读 M5 模型的 dataScope 声明。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private DataScopeHelper dataScopeHelper;

    @Around("@annotation(com.jjy.infrastructure.datascope.DataScope) "
          + "|| @within(com.jjy.infrastructure.datascope.DataScope)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        DataScope ann = resolveAnnotation(pjp);
        if (ann == null) {
            return pjp.proceed();
        }
        String domain = ann.value();
        String allPerm = ann.allPerm().isEmpty() ? (domain + ":query-all") : ann.allPerm();
        String ownPerm = ann.ownPerm().isEmpty() ? (domain + ":query-own") : ann.ownPerm();

        // 数据范围优先读 M5 的 dataScope 声明；模型未声明时才回退权限码约定。
        // 这样「模型是唯一语义源」在数据权限上也成立——改 M5 即刻生效，
        // 不依赖权限码命名是否恰好写成 <domain>:query-all。
        String scopeDecl = dataScopeHelper.dataScopeOf(domain);
        boolean all;
        boolean own;
        if ("ALL".equalsIgnoreCase(scopeDecl)) {
            all = true;
            own = false;
        } else if ("OWN".equalsIgnoreCase(scopeDecl)) {
            all = false;
            own = true;
        } else {
            all = SecurityUtils.hasPermission(allPerm) || SecurityUtils.hasPermission("*");
            // PLATFORM 用户默认可看全部（即便未单独配 query-all）
            if (!all && "PLATFORM".equals(SecurityUtils.currentUserType())) all = true;
            own = SecurityUtils.hasPermission(ownPerm);
        }

        if (!all && !own) {
            throw new BizException("数据权限不足：缺少 " + allPerm + " 或 " + ownPerm);
        }

        Scope scope = all ? Scope.ALL : Scope.OWN;
        String cid = SecurityUtils.currentCustomerId();
        try {
            DataScopeContext.set(scope, cid, domain);
            if (log.isDebugEnabled()) {
                log.debug("@DataScope({}) → scope={}, customerId={}, user={}", domain, scope, cid, SecurityUtils.currentUsername());
            }
            return pjp.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    private DataScope resolveAnnotation(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        DataScope ann = m.getAnnotation(DataScope.class);
        if (ann == null) {
            ann = m.getDeclaringClass().getAnnotation(DataScope.class);
        }
        return ann;
    }
}
