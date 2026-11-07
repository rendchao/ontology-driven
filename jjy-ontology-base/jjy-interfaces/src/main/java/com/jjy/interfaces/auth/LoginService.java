package com.jjy.interfaces.auth;

import com.jjy.infrastructure.security.JwtUtil;
import com.jjy.infrastructure.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录服务：负责签发令牌并组装登录响应。
 *
 * <p>认证主体（查库、密码校验、角色权限装配）通过 {@link UserAuthenticator} 扩展点注入，
 * 业务工程实现该接口即可接入真实用户体系，底座不绑定任何具体用户表。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class LoginService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserAuthenticator authenticator;

    /**
     * 认证用户名口令。返回 null 表示认证失败。
     */
    public LoginUser authenticate(String username, String password) {
        return authenticator.authenticate(username, password);
    }

    /**
     * 组装登录响应：访问令牌 + 刷新令牌 + 用户档案。
     */
    public Map<String, Object> buildLoginPayload(LoginUser user) {
        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", jwtUtil.generateToken(user));
        data.put("refreshToken", jwtUtil.generateRefreshToken(user));
        data.put("expiresIn", jwtUtil.getExpirationSeconds());
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("fullName", user.getFullName());
        data.put("userType", user.getUserType());
        data.put("customerId", user.getCustomerId());
        data.put("currentTenantId", user.getCurrentTenantId());
        data.put("roleIds", user.getRoleIds());
        data.put("permissions", user.getPermissions());
        return data;
    }
}
