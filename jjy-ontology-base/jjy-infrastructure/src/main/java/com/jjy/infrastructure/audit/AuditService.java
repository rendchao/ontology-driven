package com.jjy.infrastructure.audit;

import com.jjy.infrastructure.persistence.mapper.AuditLogMapper;
import com.jjy.infrastructure.persistence.po.AuditLogPO;
import com.jjy.infrastructure.security.SecurityUtils;
import com.jjy.infrastructure.util.IdGen;
import com.jjy.infrastructure.web.TraceIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志服务（异步写入）
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class AuditService {

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Async
    public void log(String action, String targetType, String targetId, String before, String after,
                    String ip, String userAgent) {
        try {
            AuditLogPO po = new AuditLogPO();
            po.setLogId(IdGen.uuid());
            po.setActorId(SecurityUtils.currentUserId());
            po.setActorType(SecurityUtils.currentUserType());
            po.setAction(action);
            po.setTargetType(targetType);
            po.setTargetId(targetId);
            po.setBeforeSnapshot(before);
            po.setAfterSnapshot(after);
            po.setIpAddress(ip);
            po.setUserAgent(userAgent);
            // P8 横切-链路追踪：从 MDC 取 traceId 落库
            po.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
            po.setOperationTime(LocalDateTime.now());
            auditLogMapper.insert(po);
        } catch (Exception e) {
            log.error("[Audit] 写入审计日志失败: action={}, target={}/{}", action, targetType, targetId, e);
        }
    }
}
