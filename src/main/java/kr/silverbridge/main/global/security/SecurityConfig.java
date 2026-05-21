package kr.silverbridge.main.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.jwt.JwtAuthenticationFilter;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CORS 정책 적용 (CorsConfigurationSource Bean 사용)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // REST API는 CSRF 불필요
                .csrf(AbstractHttpConfigurer::disable)

                // 보안 응답 헤더 명시 (A-L2)
                // - HSTS: nginx HTTPS 종료 후에도 X-Forwarded-Proto=https 로 보안 요청으로 인식되어 적용
                // - X-Frame-Options DENY / X-Content-Type-Options nosniff / Referrer-Policy
                // - CSP: 동일 체인의 Swagger UI(인라인 script/style 사용)와 호환되도록 unsafe-inline 허용.
                //   JSON API라 CSP의 XSS 차단 가치는 제한적이나 frame-ancestors/object-src/base-uri 하드닝은 유효.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; "
                                        + "font-src 'self' data:; "
                                        + "frame-ancestors 'none'; "
                                        + "object-src 'none'; "
                                        + "base-uri 'self'")))

                // JWT 방식 — 서버에 세션 미사용
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 인증 실패(미인증 상태로 보호 자원 접근) 시 JSON 401 반환
                // — JwtAuthenticationFilter.sendError와 동일 포맷으로 응답 일관성 유지
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"success\":false,\"message\":\"로그인이 필요합니다.\"}");
                }))

                // 경로별 접근 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 로그아웃은 /api/auth 하위지만 인증 필요 — permitAll에서 명시적으로 분리 (L-3)
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        // 인증 없이 접근 가능한 경로
                        .requestMatchers(
                                "/api/auth/**",
                                "/ws/**",          // WebSocket 핸드셰이크 (JWT는 핸드셰이크 인터셉터에서 검증)
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/actuator/health",
                                "/actuator/health/**",   // liveness/readiness probe
                                "/actuator/info"
                        ).permitAll()
                        // 관리자 전용 경로
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )

                // JWT 필터를 Spring Security 인증 필터 앞에 등록
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate, objectMapper),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * CORS 정책
     * - Allowed origins: app.cors.allowed-origins 설정값 (쉼표 구분)
     * - Methods: 주요 HTTP 메서드 + OPTIONS(preflight)
     * - Credentials: true (Authorization 헤더 사용을 위해 필수)
     * - 응답 헤더 노출: Authorization (재발급 토큰 헤더 반환 시 대비)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // 비밀번호 암호화 (BCrypt) — strength 12 (OWASP 2025 권장). 기존 strength 10 해시도 검증은 호환됨
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
