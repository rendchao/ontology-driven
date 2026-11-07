package com.jjy.infrastructure.security;

import com.jjy.infrastructure.persistence.mapper.security.ApiCredentialMapper;
import com.jjy.infrastructure.persistence.po.security.ApiCredentialPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * API 凭证服务
 * <p>P6 M1 集成模型：外部系统 API Key + Secret 查询与有效性校验</p>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class ApiCredentialService {

    @Autowired
    private ApiCredentialMapper apiCredentialMapper;

    /**
     * 根据 API Key 查找凭证
     * @param apiKey 外部系统传来的 X-Api-Key
     * @return 凭证对象，找不到返回 null
     */
    public ApiCredentialPO findByApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return null;
        return apiCredentialMapper.findByApiKey(apiKey);
    }

    /**
     * 校验凭证是否可用（启用 + 未过期）
     */
    public boolean isActive(ApiCredentialPO po) {
        if (po == null) return false;
        if (po.getStatus() == null || po.getStatus() != 1) return false;
        if (po.getExpiresAt() != null && po.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("[ApiCredential] 凭证已过期: apiKey={}, expiresAt={}", po.getApiKey(), po.getExpiresAt());
            return false;
        }
        return true;
    }

    /**
     * 转 ApiKeyContext
     */
    public ApiKeyContext toContext(ApiCredentialPO po) {
        if (po == null) return null;
        return new ApiKeyContext(po.getApiKey(), po.getSystemCode(), po.getSystemName(), po.getId());
    }
}
