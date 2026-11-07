package com.jjy.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 安全上下文工具：从 Spring Security 上下文读取当前登录用户
 *
 * @author jjy-base
 */
public final class SecurityUtils {

    public static final String ROLE_PLATFORM_ADMIN = "ROLE-PLATFORM-ADMIN";
    public static final String AUTH_ALL = "*";

    private SecurityUtils() {}

    public static LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        if (auth.getPrincipal() instanceof LoginUser) {
            return (LoginUser) auth.getPrincipal();
        }
        return null;
    }

    public static String currentUserId() {
        LoginUser u = currentUser();
        return u == null ? null : u.getUserId();
    }

    public static String currentUsername() {
        LoginUser u = currentUser();
        return u == null ? null : u.getUsername();
    }

    public static String currentUserType() {
        LoginUser u = currentUser();
        return u == null ? null : u.getUserType();
    }

    public static String currentCustomerId() {
        // Step 10: 从 token 取 currentTenantId（多租户用户切换用）
        // 兼容老 LoginUser（没 currentTenantId 字段时回退到 customerId）
        LoginUser u = currentUser();
        if (u == null) return null;
        if (u.getCurrentTenantId() != null && !u.getCurrentTenantId().isEmpty()) {
            return u.getCurrentTenantId();
        }
        return u.getCustomerId();
    }

    /** 当前激活租户 id（多租户用户切换用，从 token claim 读取） */
    public static String currentTenantId() {
        LoginUser u = currentUser();
        if (u == null) return null;
        if (u.getCurrentTenantId() != null && !u.getCurrentTenantId().isEmpty()) {
            return u.getCurrentTenantId();
        }
        return u.getCustomerId();
    }

    public static Set<String> currentPermissions() {
        LoginUser u = currentUser();
        if (u == null || u.getPermissions() == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(u.getPermissions());
    }

    public static boolean isAuthenticated() {
        return currentUser() != null;
    }

    public static boolean hasPermission(String permission) {
        if (!isAuthenticated()) {
            return false;
        }
        Set<String> perms = currentPermissions();
        if (perms.contains(AUTH_ALL)) {
            return true;
        }
        return perms.contains(permission);
    }

    public static boolean hasAnyPermission(String... permissions) {
        if (permissions == null || permissions.length == 0) {
            return true;
        }
        if (!isAuthenticated()) {
            return false;
        }
        Set<String> perms = currentPermissions();
        if (perms.contains(AUTH_ALL)) {
            return true;
        }
        for (String p : permissions) {
            if (perms.contains(p)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAllPermissions(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return true;
        }
        if (!isAuthenticated()) {
            return false;
        }
        Set<String> perms = currentPermissions();
        if (perms.contains(AUTH_ALL)) {
            return true;
        }
        for (String p : permissions) {
            if (!perms.contains(p)) {
                return false;
            }
        }
        return true;
    }

    public static void requirePermission(String permission) {
        if (!hasPermission(permission)) {
            throw new org.springframework.security.access.AccessDeniedException("缺少权限: " + permission);
        }
    }

    public static Collection<? extends GrantedAuthority> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Collections.emptyList() : auth.getAuthorities();
    }

    /**
     * 当前 API Key 上下文（仅 P6 外部系统对接链生效，JWT 链返回 null）
     */
    public static ApiKeyContext currentApiKeyContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return null;
        if (auth.getPrincipal() instanceof ApiKeyContext) {
            return (ApiKeyContext) auth.getPrincipal();
        }
        return null;
    }

    /** 当前外部系统编码（API Key 鉴权后） */
    public static String currentSystemCode() {
        ApiKeyContext ctx = currentApiKeyContext();
        return ctx == null ? null : ctx.getSystemCode();
    }

    /** 当前外部 API Key（API Key 鉴权后） */
    public static String currentApiKey() {
        ApiKeyContext ctx = currentApiKeyContext();
        return ctx == null ? null : ctx.getApiKey();
    }
}
