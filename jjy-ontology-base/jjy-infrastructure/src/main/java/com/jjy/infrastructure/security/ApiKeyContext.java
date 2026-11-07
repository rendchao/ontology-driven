package com.jjy.infrastructure.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * API Key 鉴权后的上下文（外部系统身份）
 * <p>由 {@link ApiKeyAuthFilter} 写入 SecurityContext，业务侧通过 {@link SecurityUtils#currentApiKeyContext()} 读取。</p>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** API Key（外部系统标识） */
    private String apiKey;

    /** 系统码：SAP / CUSTOMER / PORTAL / WMS / TMS / BMS / SIGNOFF / B2B */
    private String systemCode;

    /** 系统中文名 */
    private String systemName;

    /** 凭证主键 ID */
    private Long credentialId;
}
