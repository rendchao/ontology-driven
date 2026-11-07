package com.jjy.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * JWT 工具类
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-seconds:86400}")
    private long expirationSeconds;

    @Value("${jwt.refresh-expiration-seconds:604800}")
    private long refreshExpirationSeconds;

    @Value("${jwt.issuer:jjy-base}")
    private String issuer;

    private SecretKey getKey() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(bytes);
    }

    public String generateToken(LoginUser user) {
        return generateToken(user, expirationSeconds, "access");
    }

    public String generateRefreshToken(LoginUser user) {
        return generateToken(user, refreshExpirationSeconds, "refresh");
    }

    private String generateToken(LoginUser user, long ttlSeconds, String tokenType) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000L);
        return Jwts.builder()
                .setSubject(user.getUserId())
                .setIssuer(issuer)
                .setIssuedAt(now)
                .setExpiration(exp)
                .claim("username", user.getUsername())
                .claim("fullName", user.getFullName())
                .claim("userType", user.getUserType())
                .claim("customerId", user.getCustomerId())
                .claim("currentTenantId", user.getCurrentTenantId())
                .claim("roleIds", user.getRoleIds())
                .claim("permissions", user.getPermissions())
                .claim("tokenType", tokenType)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isValid(String token) {
        return parse(token) != null;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
