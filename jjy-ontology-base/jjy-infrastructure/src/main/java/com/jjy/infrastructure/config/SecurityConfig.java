package com.jjy.infrastructure.config;

import com.jjy.infrastructure.security.ApiKeyAuthFilter;
import com.jjy.infrastructure.security.JwtAuthFilter;
import com.jjy.infrastructure.web.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置
 *
 * <p>P6 采用单链 + 双 Filter 策略：
 * <ul>
 *   <li>{@link ApiKeyAuthFilter} 对所有请求生效，但内部判断 URI 前缀，
 *       命中 /sap /customer /portal /signoff /bms /webhook 时执行 API Key + HMAC 鉴权；
 *       其它路径直接放行给后续的 JWT 链。</li>
 *   <li>{@link JwtAuthFilter} 继续处理 JWT 鉴权（保持 P1-P5 行为不变）</li>
 * </ul>
 *
 * <p>这种设计比多 SecurityFilterChain 更稳：避免 5.7 多链优先级错乱、
 * 也避免 inbound filter 误伤 /auth/login 等共享路径。</p>
 *
 * @author jjy-base
 */
@Slf4j
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "jjy.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private ApiKeyAuthFilter apiKeyAuthFilter;

    @Value("${jjy.cors.allowed-origins:http://127.0.0.1,http://localhost,http://localhost:8080,http://localhost:8081,http://127.0.0.1:8081,http://localhost:8082,http://127.0.0.1:8082,http://localhost:8083,http://127.0.0.1:8083,http://localhost:8084,http://127.0.0.1:8084,http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(allowedOrigins);
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cfg.setAllowedHeaders(Arrays.asList("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .cors().and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers("/auth/login", "/auth/refresh", "/auth/captcha").permitAll()
                .antMatchers("/health", "/actuator/**", "/doc.html", "/webjars/**", "/swagger-resources/**", "/v2/api-docs/**", "/druid/**", "/favicon.ico", "/error").permitAll()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
                .and()
                .headers().frameOptions().disable()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint((req, resp, e) -> writeJson(resp, R.CODE_UNAUTHORIZED, "未登录或登录已过期"))
                .accessDeniedHandler((req, resp, e) -> writeJson(resp, R.CODE_FORBIDDEN, "无权访问"))
                .and()
                // inbound filter 排在 JWT filter 之前：inbound 路径会被 ApiKeyAuthFilter 处理（不进入 JWT）；
                // 其它路径 ApiKeyAuthFilter 直接放行，再由 JwtAuthFilter 接手
                // 注意：addFilterBefore 的 anchor 必须是 Spring Security 已知 filter 类，
                //       因此两个 filter 共用 UsernamePasswordAuthenticationFilter 作为 anchor，
                //       实际执行顺序 = addFilterBefore 的调用顺序（ApiKey 先于 JWT）
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(javax.servlet.http.HttpServletResponse resp, int code, String message) throws java.io.IOException {
        resp.setStatus(javax.servlet.http.HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        ObjectMapper m = new ObjectMapper();
        resp.getWriter().write(m.writeValueAsString(R.fail(code, message)));
    }
}
