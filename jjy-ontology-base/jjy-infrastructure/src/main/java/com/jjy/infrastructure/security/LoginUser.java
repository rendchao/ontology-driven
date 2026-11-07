package com.jjy.infrastructure.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * 登录用户上下文（写入 JWT claim，由 JwtAuthFilter 解析后塞入 SecurityContext）
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String username;
    private String fullName;
    private String userType;          // PLATFORM / CUSTOMER / API
    private String customerId;        // CUSTOMER 用户所属客户（主租户）；PLATFORM 为空
    /**
     * 当前激活租户（多租户用户切换用，写入 JWT claim）
     * <p>登录时默认取 t_user_tenant.is_default=1 行；没有 default 则取第一条；都没有则回退到 customerId</p>
     * <p>切换租户时由 {@code /auth/switch-tenant} 重新签发 JWT 覆盖</p>
     */
    private String currentTenantId;
    private List<String> roleIds;
    private Set<String> permissions;  // 权限码集合
}
