package com.jjy.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限判定工具 Bean（用于 @PreAuthorize/@PreFilter 表达式）
 *
 * 兼容 ADMIN 的 "*" 通配：当 authorities 含 "*" 时，任何 has(...) 都返回 true。
 *
 * 用法：
 *   @PreAuthorize("@perm.has('system:dict:query')")
 *   @PreAuthorize("@perm.hasAny('customer:create', 'customer:approve')")
 */
@Component("perm")
public class PermService {

    /** 当前用户的所有 authorities（含 "*"） */
    public Set<String> currentAuthorities() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getAuthorities() == null) return java.util.Collections.emptySet();
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /** 是否拥有指定权限（含 "*" 通配） */
    public boolean has(String perm) {
        Set<String> mine = currentAuthorities();
        return mine.contains("*") || mine.contains(perm);
    }

    /** 是否拥有任一权限 */
    public boolean hasAny(String... perms) {
        Set<String> mine = currentAuthorities();
        if (mine.contains("*")) return true;
        for (String p : perms) if (mine.contains(p)) return true;
        return false;
    }

    /** 是否拥有全部权限 */
    public boolean hasAll(String... perms) {
        Set<String> mine = currentAuthorities();
        if (mine.contains("*")) return true;
        for (String p : perms) if (!mine.contains(p)) return false;
        return perms.length > 0;
    }
}
