package com.jjy.infrastructure.integration;

import com.jjy.infrastructure.persistence.mapper.integration.InboundRequestMapper;
import com.jjy.infrastructure.persistence.po.integration.InboundRequestPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 入站请求审计服务
 * <p>P6 M1 集成模型：8 个 REST_INBOUND 端点每次请求写一条 t_inbound_request 记录</p>
 *
 * <p>注意：用 REQUIRES_NEW 让审计失败不影响主业务事务</p>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class InboundRequestService {

    @Autowired
    private InboundRequestMapper mapper;

    /**
     * 写一条入站请求审计
     *
     * @param interfaceId  M1 接口 ID（INT-INB-001 等）
     * @param systemCode   外部系统码（SAP / CUSTOMER / PORTAL 等）
     * @param path         请求路径
     * @param method       HTTP 方法
     * @param requestBody  请求体 JSON 原文
     * @param responseBody 响应体 JSON 原文
     * @param httpCode     HTTP 状态码
     * @param bizCode      业务返回 code
     * @param bizMsg       业务返回 message
     * @param apiKey       认证 API Key
     * @param signature    收到的签名
     * @param remoteIp     客户端 IP
     * @param durationMs   处理耗时（毫秒）
     * @param boundBehavior 绑定的 M2 behaviorId
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void log(String interfaceId, String systemCode, String path, String method,
                    String requestBody, String responseBody, int httpCode,
                    Integer bizCode, String bizMsg, String apiKey, String signature,
                    String remoteIp, long durationMs, String boundBehavior) {
        try {
            InboundRequestPO po = new InboundRequestPO();
            po.setRequestUid(UUID.randomUUID().toString().replace("-", ""));
            po.setInterfaceId(interfaceId);
            po.setSystemCode(systemCode);
            po.setPath(path);
            po.setHttpMethod(method);
            po.setRequestBody(requestBody);
            po.setResponseBody(responseBody);
            po.setResponseCode(httpCode);
            po.setBusinessCode(bizCode);
            po.setBusinessMsg(bizMsg);
            po.setApiKey(apiKey);
            po.setSignature(signature);
            po.setRemoteIp(remoteIp);
            po.setDurationMs(durationMs);
            po.setBoundBehavior(boundBehavior);
            po.setCreatedAt(LocalDateTime.now());
            po.setFlag(0);
            mapper.insert(po);
        } catch (Exception e) {
            // 审计失败不抛 - 主业务不能被审计拖死
            log.warn("[InboundAudit] 写审计失败: interfaceId={}, err={}", interfaceId, e.getMessage());
        }
    }
}
