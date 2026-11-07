package com.jjy.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjy.infrastructure.persistence.po.security.ApiCredentialPO;
import com.jjy.infrastructure.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * API Key + HMAC-SHA256 鉴权过滤器
 * <p>P6 M1 集成模型：外部系统 → 业务系统 同步 REST_INBOUND + 异步 EVENT_INBOUND webhook 鉴权</p>
 *
 * <h3>协议</h3>
 * <pre>
 * Headers:
 *   X-Api-Key:     AK-SAP-001
 *   X-Timestamp:   1730000000   (Unix epoch seconds)
 *   X-Signature:   hex(HMAC-SHA256(api_secret, X-Timestamp + "\n" + body))
 *
 * 校验:
 *   1) |now - X-Timestamp| <= 5 min（防重放）
 *   2) HMAC-SHA256(secret, ts + "\n" + body) == X-Signature（防篡改）
 *   3) X-Api-Key 在 t_api_credential 中存在且 status=1、未过期
 * </pre>
 *
 * <p>body 会被缓存，Controller 仍可继续读取。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_API_KEY   = "X-Api-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Signature";
    public static final String HMAC_ALGO        = "HmacSHA256";

    /** 时间戳容差：5 分钟 */
    private static final long TS_TOLERANCE_SECONDS = 5 * 60L;

    /**
     * 入站路径前缀（与 SecurityConfig 一致；context-path=/api/v1 已被剥离）
     * <p>命中前缀才走 API Key + HMAC 鉴权；其它路径直接放行给后续 JWT 链</p>
     */
    private static final java.util.Set<String> INBOUND_PREFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
            "/sap/", "/customer/", "/portal/", "/signoff/", "/bms/", "/webhook/"
    ));

    @Autowired
    private ApiCredentialService apiCredentialService;

    @Autowired
    private RateLimiter rateLimiter;

    @Value("${jjy.inbound.auth-enabled:true}")
    private boolean authEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // 非入站路径：直接放行（不进 HMAC 校验），让后续 JWT 链处理
        if (!isInboundPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // 必须先 wrap 才能在签名校验里读到 body
        CachedBodyRequestWrapper wrapped = (request instanceof CachedBodyRequestWrapper)
                ? (CachedBodyRequestWrapper) request
                : new CachedBodyRequestWrapper(request);

        if (!authEnabled) {
            // 测试场景：关闭鉴权时只挂一个 ANONYMOUS context
            log.debug("[ApiKeyAuth] auth-disabled, skip");
            chain.doFilter(wrapped, response);
            return;
        }

        String apiKey   = wrapped.getHeader(HEADER_API_KEY);
        String tsHeader = wrapped.getHeader(HEADER_TIMESTAMP);
        String sig      = wrapped.getHeader(HEADER_SIGNATURE);

        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(tsHeader) || !StringUtils.hasText(sig)) {
            log.warn("[ApiKeyAuth] 缺鉴权头: path={}, remoteIp={}", wrapped.getRequestURI(), wrapped.getRemoteAddr());
            writeJson(response, R.CODE_UNAUTHORIZED, "缺少 API Key / Timestamp / Signature 头");
            return;
        }

        // 1) 时间戳容差
        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (NumberFormatException e) {
            writeJson(response, R.CODE_UNAUTHORIZED, "X-Timestamp 格式错误");
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > TS_TOLERANCE_SECONDS) {
            log.warn("[ApiKeyAuth] 时间戳超容差: apiKey={}, ts={}, now={}, diff={}", apiKey, ts, now, now - ts);
            writeJson(response, R.CODE_UNAUTHORIZED, "X-Timestamp 超出 ±5 分钟容差");
            return;
        }

        // 2) 查找凭证
        ApiCredentialPO po = apiCredentialService.findByApiKey(apiKey);
        if (!apiCredentialService.isActive(po)) {
            log.warn("[ApiKeyAuth] 凭证无效/禁用/过期: apiKey={}", apiKey);
            writeJson(response, R.CODE_UNAUTHORIZED, "API Key 无效或已停用");
            return;
        }

        // 3) 限流（每个 API Key 100 QPS）
        if (!rateLimiter.tryAcquire(apiKey)) {
            log.warn("[ApiKeyAuth] 触发限流: apiKey={}", apiKey);
            response.setStatus(429);
            writeJson(response, 4290, "请求过于频繁，请稍后重试");
            return;
        }

        // 4) HMAC-SHA256 签名校验
        String body = wrapped.getBodyAsString();
        String payload = ts + "\n" + body;
        String expected = hmacSha256Hex(po.getApiSecret(), payload);
        if (!constantTimeEquals(expected, sig)) {
            log.warn("[ApiKeyAuth] 签名校验失败: apiKey={}, path={}, expected={}, got={}", apiKey, wrapped.getRequestURI(), expected, sig);
            writeJson(response, R.CODE_UNAUTHORIZED, "签名校验失败");
            return;
        }

        // 5) 写 SecurityContext（principal = ApiKeyContext）
        ApiKeyContext ctx = apiCredentialService.toContext(po);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                ctx, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_API")));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(wrapped));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(wrapped, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 计算 HMAC-SHA256 并转 hex（小写） */
    public static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败: " + e.getMessage(), e);
        }
    }

    /** 字节数组转小写 hex（Java 8 兼容，替代 JDK 17 的 HexFormat） */
    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        char[] digits = {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2]     = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }

    /** 常量时间比较（防 timing attack） */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * 判断 URI 是否属于入站对接路径
     * <p>注意：getRequestURI() 返回原始 URI（含 context-path），所以前缀里要带 /api/v1</p>
     */
    private boolean isInboundPath(String uri) {
        if (uri == null) return false;
        for (String p : INBOUND_PREFIXES) {
            // 入站路径在 context-path 后，所以完整前缀是 /api/v1 + p
            if (uri.startsWith("/api/v1" + p)) return true;
        }
        return false;
    }

    private void writeJson(HttpServletResponse resp, int code, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        ObjectMapper m = new ObjectMapper();
        resp.getWriter().write(m.writeValueAsString(R.fail(code, message)));
    }
}
