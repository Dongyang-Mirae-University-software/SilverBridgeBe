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
                //
                // 명명 규칙 = "<역할> - <도메인>", 역할은 공통 / 피보호자 / 보호자 / 관리자 4가지뿐이다.
                // "누가 쓰는 API인지 → 무슨 기능인지" 두 단계로 읽히게 하려는 것이며,
                // 역할만 적은 태그("보호자")나 도메인만 적은 태그("인증")를 새로 만들지 말 것 —
                // 전자는 그 역할의 API 전부인 것처럼 읽히고, 후자는 누가 호출하는지가 사라진다.
                //
                // ⚠️ 새 컨트롤러를 추가하면 이 목록에도 반드시 태그를 등록한다. 빠뜨리면 설명 없이
                //    목록 맨 뒤에 붙어, 최근 기능일수록 문서가 부실해진다(2026-08-07 정리 이전 상태).
                .tags(List.of(
                        // ── 공통: 역할과 무관하게 로그인한 사용자면 호출 ──────────────
                        new Tag().name("공통 - 인증")
                                .description("회원가입 / 로그인 / 로그아웃 / 토큰 재발급 / 카카오 로그인 / SMS 인증 / 이메일·비밀번호 찾기 및 재설정\n"
                                        + "※ 로그인·회원가입 관련 API는 토큰 없이 호출 가능. 로그아웃·재발급은 토큰 필요.\n"
                                        + "\n"
                                        + "[공통 응답 포맷] 모든 API가 아래 구조로 응답합니다 (null 필드는 생략).\n"
                                        + "  성공(데이터): { \"success\": true, \"data\": { ... } }\n"
                                        + "  성공(메시지): { \"success\": true, \"message\": \"처리되었습니다.\" }\n"
                                        + "  실패:        { \"success\": false, \"message\": \"오류 메시지\" }  (종류는 HTTP 상태코드로 구분)\n"
                                        + "\n"
                                        + "[토큰 사용법] 로그인으로 받은 accessToken을 우측 상단 Authorize에 입력하면\n"
                                        + "이후 인증이 필요한 모든 API에 자동 적용됩니다. Header: Authorization: Bearer {accessToken}"),
                        new Tag().name("공통 - 내 계정")
                                .description("로그인한 본인의 프로필 조회·수정 / 프로필 이미지 / 비밀번호 변경 / 회원 탈퇴\n"
                                        + "※ 피보호자·보호자·관리자가 모두 같은 경로를 씁니다(역할별로 나뉘지 않습니다).\n"
                                        + "※ Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("공통 - 알림 설정")
                                .description("FCM 푸시 토큰 등록·삭제 + 알림 채널(FCM/SMS/카카오 알림톡/이메일) ON/OFF 설정\n"
                                        + "※ 토큰 등록은 기기 단위, 채널 설정은 계정 단위입니다.\n"
                                        + "※ SOS 등 필수 알림은 이 설정과 무관하게 항상 발송됩니다.\n"
                                        + "※ Authorization: Bearer {accessToken} 헤더 필수."),
                        new Tag().name("공통 - 공지사항")
                                .description("로그인한 사용자(피보호자·보호자·관리자)가 열람하는 공지사항 조회.\n"
                                        + "※ 공지 작성·수정은 [관리자 - 공지사항] 참고.\n"
                                        + "※ Authorization: Bearer {accessToken} 헤더 필수."),

                        // ── 피보호자(WARD) 전용: 다른 역할이 호출하면 403 ──────────────
                        new Tag().name("피보호자 - 연결")
                                .description("보호자가 보낸 연결 요청 수락·거절, 연결된 보호자 목록 조회·해제\n"
                                        + "※ WARD 역할 계정만 호출 가능."),
                        new Tag().name("피보호자 - SOS")
                                .description("긴급 SOS 발생(위치 문구 선택 전송) + SOS 동작 설정(119 연결·안내 방식) 조회·변경\n"
                                        + "※ 동작 설정은 119 연결 흐름만 정합니다 — 어떤 값이어도 보호자 알림은 항상 발송됩니다.\n"
                                        + "※ WARD 역할 계정만 호출 가능."),
                        new Tag().name("피보호자 - 복약")
                                .description("오늘의 복약 일정 조회 + 복용 체크·해제\n"
                                        + "※ 복용 체크는 피보호자만 할 수 있습니다(보호자에겐 체크 API가 없습니다).\n"
                                        + "※ 약 등록·수정·삭제는 보호자 전용이라 여기 없습니다. 기준일은 항상 KST.\n"
                                        + "※ WARD 역할 계정만 호출 가능."),
                        new Tag().name("피보호자 - 카메라")
                                .description("이상감지 카메라 등록·목록·수정·삭제 (SessionID 발급)\n"
                                        + "※ 본인이 등록한 카메라만 다룰 수 있습니다.\n"
                                        + "※ WARD 역할 계정만 호출 가능."),

                        // ── 보호자(GUARDIAN) 전용: 다른 역할이 호출하면 403 ────────────
                        new Tag().name("보호자 - 연결")
                                .description("피보호자에게 연결 요청·취소, 연결된 피보호자 목록 조회·해제\n"
                                        + "※ 목록에는 수락 대기(PENDING)가 섞여 있습니다 — 다른 API에 wardId를 넘길 땐 ACTIVE만 사용하세요.\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),
                        new Tag().name("보호자 - SOS 이력")
                                .description("피보호자의 SOS 발생 이력 조회(최신순 페이징)\n"
                                        + "※ 요청 시점에 ACTIVE 연결인 피보호자의 이력만 보입니다(연결 해제 시 과거 이력도 비공개).\n"
                                        + "※ 발생 경로(SOS_BUTTON·GUARDIAN_CALL)는 이력 표시 전용이며 알림 대상을 가르지 않습니다.\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),
                        new Tag().name("보호자 - 복약")
                                .description("피보호자별 오늘 복약 현황 조회 / 약 추가·수정·삭제 / 알림 설정(피보호자별·본인 수신)\n"
                                        + "※ 약 등록·수정·삭제는 보호자만, 복용 체크는 피보호자만입니다.\n"
                                        + "※ ACTIVE 연결된 피보호자만 대상입니다.\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),
                        new Tag().name("보호자 - 이상감지")
                                .description("피보호자의 이상감지 이력 조회(상황 단위, 최신순 페이징) + 오탐 응답 + 확인 요청 알림 설정\n"
                                        + "※ 단위는 \"상황\"입니다 — 10분 이내 연속 감지는 한 건으로 묶입니다.\n"
                                        + "※ 실제 위험이었는지는 보호자만 판정합니다(1인 1표, 번복 가능). 답이 갈리면 CONFLICTED로 관리자가 확인합니다.\n"
                                        + "※ 요청 시점에 ACTIVE 연결인 피보호자의 이력만 보입니다(연결 해제 시 과거 이력도 비공개).\n"
                                        + "※ 응답은 이미 나간 알림을 되돌리지 않습니다.\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),
                        new Tag().name("보호자 - 카메라")
                                .description("연결된 피보호자의 카메라 목록 조회(실시간 영상 연동용)\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),
                        new Tag().name("보호자 - 문의")
                                .description("고객센터 문의 작성 및 본인 문의 목록·상세 조회.\n"
                                        + "카테고리: ANOMALY(이상감지)/HOSPITAL(병원)/ACCOUNT(계정·회원)/SERVICE(서비스 이용)/ETC(기타), 상태: WAITING(답변 대기)/ANSWERED(답변 완료).\n"
                                        + "※ GUARDIAN 역할 계정만 호출 가능."),

                        // ── 관리자(ADMIN) 전용 ────────────────────────────────────
                        new Tag().name("관리자 - 공지사항")
                                .description("공지 CRUD 및 임시저장 — 게시된 공지 관리와 작성 중 공지(임시저장) 보관/게시 전환.\n"
                                        + "※ ADMIN 권한 계정만 호출 가능."),
                        new Tag().name("관리자 - 문의")
                                .description("전체 문의 목록(탭 카운트·필터·검색·페이징)·상세 조회 및 답변 작성.\n"
                                        + "※ ADMIN 권한 계정만 호출 가능.")
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
                    // GET·PUT·DELETE /me 는 단일 경로 키 → /me 한 줄로 묶임
                    "/api/user/me",
                    "/api/user/me/password",
                    "/api/user/me/image",
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
                    "/api/admin/announcement/draft/publish/{id}",
                    // ── [피보호자 - 연결] ────────────────────────────
                    "/api/ward/connection/select",
                    // ── [피보호자 - SOS] ─────────────────────────────
                    "/api/ward/sos",
                    "/api/ward/sos-setting",
                    // ── [피보호자 - 복약] ────────────────────────────
                    "/api/ward/medication/today",
                    "/api/ward/medication/{medicationId}/intake",
                    // ── [보호자 - 연결] ──────────────────────────────
                    "/api/guardian/connection/select",
                    // ── [보호자 - SOS 이력] ──────────────────────────
                    "/api/guardian/sos/history",
                    // ── [보호자 - 복약] ──────────────────────────────
                    "/api/guardian/medication",
                    "/api/guardian/ward/{wardId}/medication",
                    "/api/guardian/medication/{medicationId}",
                    "/api/guardian/ward/{wardId}/medication-setting",
                    "/api/guardian/ward/{wardId}/medication-alert-setting",
                    // ── [보호자 - 문의] ──────────────────────────────
                    "/api/guardian/anomaly/history",
                    "/api/guardian/anomaly/{incidentId}/feedback",
                    "/api/guardian/anomaly/reminder-setting",

                    // POST(작성)·GET(내 목록)은 단일 경로 키 → /api/guardian/inquiry 한 줄로 묶임
                    "/api/guardian/inquiry",
                    "/api/guardian/inquiry/{id}",
                    // ── [관리자 - 문의] ──────────────────────────────
                    "/api/admin/inquiry",
                    "/api/admin/inquiry/{id}",
                    "/api/admin/inquiry/{id}/answer"
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
