package com.jjy.interfaces.auth;

import com.jjy.infrastructure.security.LoginUser;

/**
 * 用户认证扩展点。
 *
 * <p>底座**不绑定任何具体用户表**：业务工程实现本接口并注册为 Spring Bean，
 * 即可接管登录认证（查库、口令校验、角色权限装配）。</p>
 *
 * <p>实现类必须是唯一的 Bean；底座自带的 {@code DemoUserAuthenticator} 仅在
 * 未检测到业务实现时生效（见 {@code AuthConfig} 的条件装配）。</p>
 *
 * @author jjy-base
 */
public interface UserAuthenticator {

    /**
     * 认证用户名与口令。
     *
     * @param username 用户名
     * @param password 明文口令
     * @return 认证成功返回登录用户上下文（含角色与权限码）；失败返回 null
     */
    LoginUser authenticate(String username, String password);
}
