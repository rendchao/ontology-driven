package com.jjy.infrastructure.security;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JWT 鉴权过滤器：从 Authorization 头解析 JWT，写入 SecurityContext
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            Claims claims = jwtUtil.parse(token);
            if (claims != null && "access".equals(claims.get("tokenType"))) {
                LoginUser user = new LoginUser();
                user.setUserId(claims.getSubject());
                user.setUsername((String) claims.get("username"));
                user.setFullName((String) claims.get("fullName"));
                user.setUserType((String) claims.get("userType"));
                user.setCustomerId((String) claims.get("customerId"));
                // 兼容老 token：没有 currentTenantId 时回退到 customerId
                Object cti = claims.get("currentTenantId");
                user.setCurrentTenantId(cti != null ? cti.toString() : user.getCustomerId());
                @SuppressWarnings("unchecked")
                List<String> roleIds = (List<String>) claims.get("roleIds");
                user.setRoleIds(roleIds == null ? Collections.emptyList() : roleIds);
                @SuppressWarnings("unchecked")
                List<String> perms = (List<String>) claims.get("permissions");
                user.setPermissions(perms == null ? Collections.emptySet() : new HashSet<>(perms));

                List<SimpleGrantedAuthority> authorities = user.getPermissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
