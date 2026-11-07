package com.jjy.interfaces.auth;

import com.jjy.domain.registry.ModelRegistry;
import com.jjy.infrastructure.persistence.mapper.UserMapper;
import com.jjy.infrastructure.persistence.po.UserPO;
import com.jjy.infrastructure.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证装配。
 *
 * <p>若业务工程未提供 {@link UserAuthenticator}，装配一个<b>数据库认证器</b>，
 * 让新工程克隆后能立即跑通「登录 → 调用受保护端点」的端到端联调。</p>
 *
 * <p><b>它做什么</b>：查 {@code t_user}（BCrypt 校验口令）→ 读用户角色 →
 * 按 <b>M5 主体模型</b>把角色展开成权限码。因此权限的来源是模型，不是代码：
 * 在 M5 里给角色加一个 permission，登录后就自动拥有该权限。</p>
 *
 * <p><b>它不做什么</b>：不硬编码任何账号口令（口令密文由迁移脚本的种子数据提供），
 * 也不硬编码权限码（权限码只来自 M5）。</p>
 *
 * <p><b>生产接入前必须替换</b>：真实工程通常有独立的用户中心、租户切换、
 * 登录失败锁定策略等，这些不在底座范围内。替换方式：实现 {@link UserAuthenticator}
 * 并注册为 Spring Bean，本装配会自动让位。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Configuration
public class AuthConfig {

    @Bean
    @ConditionalOnMissingBean(UserAuthenticator.class)
    public UserAuthenticator dbUserAuthenticator(UserMapper userMapper,
                                                 PasswordEncoder passwordEncoder,
                                                 ModelRegistry registry) {
        log.warn("[Auth] 未检测到业务 UserAuthenticator 实现，装配数据库认证器"
                + "（读 t_user + 按 M5 模型授予权限）。"
                + "生产工程若使用独立用户中心，请实现 UserAuthenticator 覆盖本装配。");
        return new DbUserAuthenticator(userMapper, passwordEncoder, registry);
    }

    /**
     * 数据库认证器：查库验口令 + 按 M5 展开角色权限。
     */
    static class DbUserAuthenticator implements UserAuthenticator {

        /** 管理员角色：拥有全部权限（通配符） */
        private static final String ADMIN_ROLE_CODE = "admin";

        private final UserMapper userMapper;
        private final PasswordEncoder passwordEncoder;
        private final ModelRegistry registry;

        DbUserAuthenticator(UserMapper userMapper, PasswordEncoder passwordEncoder, ModelRegistry registry) {
            this.userMapper = userMapper;
            this.passwordEncoder = passwordEncoder;
            this.registry = registry;
        }

        @Override
        public LoginUser authenticate(String username, String password) {
            if (username == null || username.trim().isEmpty()) {
                return null;
            }
            UserPO po;
            try {
                po = userMapper.findByUsername(username.trim());
            } catch (Exception e) {
                log.error("[Auth] 查询用户失败: username={} err={}", username, e.getMessage());
                return null;
            }
            if (po == null) {
                return null;
            }
            // 口令校验
            if (password == null || password.isEmpty()
                    || po.getPasswordHash() == null
                    || !passwordEncoder.matches(password, po.getPasswordHash())) {
                return null;
            }
            // 状态校验：仅 ACTIVE 可登录
            if (po.getStatus() != null && !"ACTIVE".equalsIgnoreCase(po.getStatus())) {
                log.warn("[Auth] 用户状态不可登录: username={} status={}", username, po.getStatus());
                return null;
            }
            // 锁定校验
            if (po.getLockedUntil() != null && po.getLockedUntil().isAfter(LocalDateTime.now())) {
                log.warn("[Auth] 用户已锁定: username={} until={}", username, po.getLockedUntil());
                return null;
            }

            List<String> roleIds = parseRoleIds(po.getRoleIds());
            Set<String> permissions = resolvePermissions(roleIds);

            LoginUser user = new LoginUser();
            user.setUserId(po.getUserId());
            user.setUsername(po.getUsername());
            user.setFullName(po.getFullName());
            user.setUserType(po.getUserType());
            user.setRoleIds(roleIds);
            user.setPermissions(permissions);
            return user;
        }

        /**
         * 解析用户的角色列表（t_user.role_ids 为 CSV）。
         */
        private List<String> parseRoleIds(String csv) {
            List<String> out = new ArrayList<>();
            if (csv == null || csv.trim().isEmpty()) {
                return out;
            }
            for (String s : csv.split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
            return out;
        }

        /**
         * 按 M5 主体模型把角色展开成权限码。
         *
         * <p>M5 声明 {@code roles[].permissions}；这里按 roleId 匹配角色，
         * 收集其权限码。<b>admin 角色额外获得通配符 {@code *}。</b></p>
         *
         * <p>注意：这是底座的默认策略（管理员拥有全部权限），
         * 权限码本身一律来自 M5 模型，不在代码里硬编码。</p>
         */
        private Set<String> resolvePermissions(List<String> roleIds) {
            Set<String> perms = new LinkedHashSet<>();
            for (Object roleObj : registry.getRoles().values()) {
                if (!(roleObj instanceof Map)) {
                    continue;
                }
                Map<?, ?> role = (Map<?, ?>) roleObj;
                String roleId = String.valueOf(role.get("roleId"));
                if (!roleIds.contains(roleId)) {
                    continue;
                }
                Object permsObj = role.get("permissions");
                if (permsObj instanceof List) {
                    for (Object p : (List<?>) permsObj) {
                        if (p != null) {
                            perms.add(String.valueOf(p));
                        }
                    }
                }
            }
            // 管理员角色：全部权限
            if (roleIds.contains("ROLE-ADMIN") || roleIds.contains(ADMIN_ROLE_CODE)) {
                perms.add("*");
            }
            return perms;
        }
    }
}
