package com.flashsale.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.result.ResultCode;
import com.flashsale.common.result.ResultVO;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.flashsale.common.util.JwtUtil;
import com.flashsale.service.filter.JwtAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class ApiSecurityConfig {
        
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public ApiSecurityConfig(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    /**
     * Actuator 监控端点链.
     * <p>
     * 必须显式声明：下面的 {@code /api/**} 链不匹配 actuator 路径，
     * 而 Spring Security 对没有任何链匹配的请求会直接放行，
     * 导致 {@code /actuator/env}、{@code /actuator/beans} 可被未授权读取。
     * Prometheus 抓取与健康检查所需的路径保持放行，其余端点要求管理员身份。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtUtil),
                        UsernamePasswordAuthenticationFilter.class
                );
        applyErrorResponses(http);

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        // 与网关 AuthGlobalFilter 的公开清单是同一份口径，两处必须一起改：
                        // 网关放行只意味着请求能到这里，api 仍会按本清单二次判定。
                        // id 限定为纯数字，避免未登录请求借任意子路径打到 /{id} 处理器上。
                        .requestMatchers(HttpMethod.GET, "/api/flash-sale/active", "/api/flash-sale/{id:\\d+}").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtUtil),
                        UsernamePasswordAuthenticationFilter.class
                );
        applyErrorResponses(http);

        return http.build();
    }

    /**
     * 统一「没登录」与「没权限」的返回码。
     * <p>
     * Spring Security 默认的 {@code Http403ForbiddenEntryPoint} 把两者都返回 403，前端因此
     * 分不清「该跳登录」还是「登录了但没权限」；而网关对未带凭据返回的是 401。这里按网关口径
     * 对齐：无有效凭据 401，凭据有效但权限不足 403，响应体统一用 {@link ResultVO} 包住。
     */
    private void applyErrorResponses(HttpSecurity http) throws Exception {
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeResult(response, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED))
                .accessDeniedHandler((request, response, deniedException) ->
                        writeResult(response, HttpStatus.FORBIDDEN, ResultCode.FORBIDDEN)));
    }

    private void writeResult(HttpServletResponse response, HttpStatus status, ResultCode code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ResultVO.fail(code));
    }
}
