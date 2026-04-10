package kr.silverbridge.main.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
}
