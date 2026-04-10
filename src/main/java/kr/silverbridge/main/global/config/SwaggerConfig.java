package kr.silverbridge.main.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SilverBridge Main API")
                        .description("SilverBridge Main 백엔드 API 문서")
                        .version("v1.0.0"))
                // Swagger UI 태그 순서 및 설명 정의 (표시 순서 = 리스트 순서)
                .tags(List.of(
                        new Tag().name("인증")
                                .description("회원가입 / 로그인 / 로그아웃 / 이메일·카카오 인증 / SMS 인증 / 비밀번호 찾기 및 재설정"),
                        new Tag().name("사용자")
                                .description("내 정보 조회 및 수정 / 비밀번호 변경 / 회원 탈퇴"),
                        new Tag().name("관리자")
                                .description("사용자 관리 및 접속 로그 조회 (ADMIN 권한 필요)")
                ))
                // 전역 JWT Bearer 인증 적용
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    // 인증 태그 API 표시 순서 제어 (프론트엔드 구현 순서 기준)
    @Bean
    public OpenApiCustomizer operationOrderCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;

            // 프론트엔드가 구현해야 하는 순서
            List<String> desiredOrder = List.of(
                    // 1. 로그인/인증 핵심
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/logout",
                    // 2. 일반 회원가입 플로우
                    "/api/auth/email/check",
                    "/api/auth/sms/send",
                    "/api/auth/sms/verify",
                    "/api/auth/register",
                    // 3. 카카오 회원가입 플로우
                    "/api/auth/kakao",
                    "/api/auth/kakao/register",
                    // 4. 이메일 찾기
                    "/api/auth/find-email",
                    // 5. 비밀번호 재설정 플로우
                    "/api/auth/password/reset-request",
                    "/api/auth/password/sms/send",
                    "/api/auth/password/sms/verify",
                    "/api/auth/password/reset"
            );

            Map<String, PathItem> original = new LinkedHashMap<>(openApi.getPaths());
            openApi.getPaths().clear();

            // 지정된 순서대로 먼저 추가
            for (String path : desiredOrder) {
                PathItem item = original.get(path);
                if (item != null) openApi.getPaths().put(path, item);
            }

            // 나머지 경로 (사용자, 관리자 등) 순서 유지하여 추가
            original.forEach((path, item) -> {
                if (!desiredOrder.contains(path)) openApi.getPaths().put(path, item);
            });
        };
    }
}
