package com.jjy.interfaces.auth;

import com.jjy.infrastructure.security.LoginUser;
import com.jjy.infrastructure.security.SecurityUtils;
import com.jjy.infrastructure.web.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点。
 *
 * <p>底座只提供「令牌签发」这一件事，**不负责查库**：认证主体（用户表、密码校验、角色权限装配）
 * 属于业务域，由业务工程自行实现并注册为 {@link AuthenticationProvider} 类型的 Bean，
 * 或直接替换本控制器。</p>
 *
 * <p>底座默认装配的 {@code DemoAuthenticationProvider} 仅用于让新工程能立即跑通端到端联调，
 * 生产接入前必须替换（见 README「必做替换项」）。</p>
 *
 * @author jjy-base
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private LoginService loginService;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (req == null || req.getUsername() == null || req.getUsername().trim().isEmpty()) {
            return R.fail(R.CODE_BAD_REQUEST, "用户名不能为空");
        }
        LoginUser user = loginService.authenticate(req.getUsername(), req.getPassword());
        if (user == null) {
            return R.fail(R.CODE_UNAUTHORIZED, "用户名或密码错误");
        }
        return R.ok(loginService.buildLoginPayload(user), "登录成功");
    }

    /**
     * 当前登录用户档案。
     *
     * <p>前端启动时用它恢复登录态（token → 用户信息与权限集），
     * 避免刷新页面后权限集丢失导致菜单/按钮全部隐藏。</p>
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        LoginUser user = SecurityUtils.currentUser();
        if (user == null) {
            return R.fail(R.CODE_UNAUTHORIZED, "未登录或登录已过期");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("fullName", user.getFullName());
        data.put("userType", user.getUserType());
        data.put("customerId", user.getCustomerId());
        data.put("currentTenantId", user.getCurrentTenantId());
        data.put("roleIds", user.getRoleIds());
        data.put("permissions", user.getPermissions());
        return R.ok(data);
    }

    /**
     * 登出。
     *
     * <p>底座是无状态 JWT，登出由前端清除令牌即可；
     * 若业务需要服务端失效列表，在此处扩展。</p>
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        return R.ok(null, "已登出");
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
