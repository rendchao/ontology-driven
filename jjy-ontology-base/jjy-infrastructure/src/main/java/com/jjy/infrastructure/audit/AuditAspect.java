package com.jjy.infrastructure.audit;

import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

/**
 * 审计切面：拦截 @Auditable 方法
 *
 * @author jjy-base
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    @Autowired
    private AuditService auditService;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result = pjp.proceed();
        try {
            String targetId = resolveTargetId(pjp, auditable);
            String ip = null;
            String ua = null;
            ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (sra != null) {
                HttpServletRequest req = sra.getRequest();
                ip = clientIp(req);
                ua = req.getHeader("User-Agent");
            }
            String after = auditable.captureAfter() ? safeToJson(pjp.getArgs()) : null;
            auditService.log(auditable.action(), auditable.targetType(), targetId, null, after, ip, ua);
        } catch (Exception e) {
            log.warn("[Audit] 审计写入异常", e);
        }
        return result;
    }

    private String resolveTargetId(ProceedingJoinPoint pjp, Auditable a) {
        if (a.targetIdSpel() != null && !a.targetIdSpel().isEmpty()) {
            try {
                MethodSignature ms = (MethodSignature) pjp.getSignature();
                Method m = ms.getMethod();
                String[] names = paramNames.getParameterNames(m);
                Object[] args = pjp.getArgs();
                EvaluationContext ctx = new StandardEvaluationContext();
                if (names != null) {
                    for (int i = 0; i < names.length; i++) {
                        ctx.setVariable(names[i], args[i]);
                    }
                }
                Expression exp = parser.parseExpression(a.targetIdSpel());
                Object v = exp.getValue(ctx);
                return v == null ? null : v.toString();
            } catch (Exception e) {
                log.debug("[Audit] SpEL 解析 targetId 失败: {}", e.getMessage());
            }
        }
        Object[] args = pjp.getArgs();
        for (Object arg : args) {
            if (arg instanceof String) {
                return (String) arg;
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = req.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) return ip;
        return req.getRemoteAddr();
    }

    private String safeToJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
