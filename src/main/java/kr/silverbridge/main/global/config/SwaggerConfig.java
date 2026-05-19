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
                                .description("회원가입 / 로그인 / 로그아웃 / 이메일·카카오 인증 / SMS 인증 / 비밀번호 찾기 및 재설정\n" +
                                        "※ 로그인·회원가입 관련 API는 토큰 없이 호출 가능. 로그아웃은 토큰 필요."),
                        new Tag().name("사용자")
                                .description("내 정보 조회 및 수정 / 비밀번호 변경 / 회원 탈퇴\n" +
                                        "※ 모든 API에 Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("보호자")
                                .description("보호자 전용 API — 피보호자 연결 관리\n" +
                                        "※ GUARDIAN 역할 계정만 호출 가능. Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("피보호자")
                                .description("피보호자 전용 API — 보호자 연결 관리\n" +
                                        "※ WARD 역할 계정만 호출 가능. Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("알림")
                                .description("FCM 푸시 알림 토큰 등록 및 삭제\n" +
                                        "※ 모든 API에 Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("피보호자/보호자 - 공지사항")
                                .description("로그인한 사용자(피보호자/보호자/관리자)가 열람하는 공지사항 조회 API.\n" +
                                        "※ Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("관리자 - 공지사항")
                                .description("공지 CRUD 및 임시저장 — 게시된 공지 관리와 작성 중 공지(임시저장) 보관/게시 전환.\n" +
                                        "※ ADMIN 권한 계정만 호출 가능. Authorization: Bearer {accessToken} 헤더 필수.")
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

    // 전체 API 표시 순서 제어 (프론트엔드 구현 순서 기준)
    @Bean
    public OpenApiCustomizer operationOrderCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;

            // 프론트엔드가 구현해야 하는 순서
            List<String> desiredOrder = List.of(
                    // ── [인증] ──────────────────────────────────────
                    "/api/auth/signin",
                    "/api/auth/signin/kakao",
                    "/api/auth/signup",
                    "/api/auth/signup/kakao",
                    "/api/auth/signup/email/check",
                    "/api/auth/signup/sms/send",
                    "/api/auth/signup/sms/verify",
                    "/api/auth/signup/sms/resend",
                    "/api/auth/find-password/email/send",
                    "/api/auth/find-password/email/verify",
                    "/api/auth/find-password/email/resend",
                    "/api/auth/find-password/sms/send",
                    "/api/auth/find-password/sms/verify",
                    "/api/auth/find-password/sms/resend",
                    "/api/auth/password/reset",
                    "/api/auth/logout",
                    "/api/auth/refresh",
                    "/api/auth/find-email",
                    // ── [사용자] ─────────────────────────────────────
                    "/api/user/me/select",
                    "/api/user/me/update",
                    "/api/user/me/update/password-change",
                    "/api/user/me/update/image-change",
                    "/api/user/me/delete",
                    // ── [관리자 - 공지사항] ──────────────────────────
                    "/api/admin/announcement/select",
                    "/api/admin/announcement/select/detail/{id}",
                    "/api/admin/announcement/create",
                    "/api/admin/announcement/update/{id}",
                    "/api/admin/announcement/delete/{id}",
                    "/api/admin/announcement/draft/select",
                    "/api/admin/announcement/draft/select/detail/{id}",
                    "/api/admin/announcement/draft/create",
                    "/api/admin/announcement/draft/update/{id}",
                    "/api/admin/announcement/draft/delete/{id}",
                    "/api/admin/announcement/draft/publish/{id}"
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
