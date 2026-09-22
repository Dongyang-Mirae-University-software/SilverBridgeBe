package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationItem;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationPeriod;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationSummaryResponse;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.service.AdminNotificationService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 알림 이력 API(보기 전용).
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨 {@code @PreAuthorize}가
 * 이중으로 담당한다(관리자 이상감지 로그와 같은 방식). 조회라서 감사 로그는 남기지 않는다.</p>
 */
@Tag(name = "관리자 - 알림 이력")
@RestController
@RequestMapping("/api/admin/notification")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @Operation(summary = "알림 이력 목록",
            description = """
                    서버가 보낸 알림을 **수신자 1명 = 1건**으로, 최신순으로 조회합니다.
                    같은 SOS가 보호자 둘에게 갔으면 2건입니다.

                    [전송 결과 - result]
                    - DELIVERED: 한 채널 이상 발송 서버가 접수
                    - SMS_FALLBACK: SOS 푸시가 실패해 문자로 대체 발송(전달된 것으로 셉니다)
                    - FAILED: 시도한 채널이 모두 실패
                    - NOT_SENT: 보내지 않음(이용 제한·탈퇴 처리 중 계정, 수신자가 채널을 꺼 둠). **실패가 아닙니다**

                    [채널]
                    - deliveredChannels: 전송 완료된 채널만. 빈 배열이면 화면에 "-"
                    - channelResults: 시도한 채널별 결과와 실패 사유(팝업용). 사유는 고정 코드이며 reasonLabel이 화면 문구입니다
                    - "전송 완료"는 발송 서버(FCM·문자 발송사)가 접수했다는 뜻입니다. 기기 표시·통신사 도달 여부는 알 수 없습니다

                    [피보호자 칸]
                    wardId는 연결 수락·거절·해제, 문의 답변, 판정 요약처럼 특정 피보호자로 정할 수 없는 알림에서 null입니다.
                    recipientIsWard=true면 피보호자 본인이 받은 알림입니다(화면 "본인").
                    relation은 지금 ACTIVE 연결의 관계 라벨이라 연결이 끝난 과거 이력에서는 null입니다.

                    [필터 - 모두 선택]
                    - category: SOS · ANOMALY · MEDICATION · OTHER(판정 요청·연결·문의). 생략하면 전체
                    - result: 위 네 값. "전송 실패" 탭은 result=FAILED
                    - period: TODAY · LAST_7_DAYS · LAST_30_DAYS. 생략하면 LAST_7_DAYS. 발송 시각 기준, KST
                    - keyword: 피보호자 또는 수신자 이름 부분일치
                    - 잘못된 category·result·period 값은 400입니다(빈 목록으로 오독되지 않게)

                    [포함되지 않는 것]
                    WebSocket 실시간 이벤트, 인증번호 문자, 이 기능 도입(2026-09-22) 이전의 발송. 보관 기간(기본 90일)이 지난 이력은 지워집니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 category·result·period 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminNotificationItem>> getLogs(
            @Parameter(description = "카테고리 (생략 시 전체)")
            @RequestParam(required = false) AdminNotificationCategory category,

            @Parameter(description = "전송 결과 (생략 시 전체)")
            @RequestParam(required = false) NotificationLogResult result,

            @Parameter(description = "기간 (생략 시 LAST_7_DAYS)")
            @RequestParam(required = false) AdminNotificationPeriod period,

            @Parameter(description = "피보호자 또는 수신자 이름 검색어 (부분일치)")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(adminNotificationService.getLogs(category, result, period, keyword, page, size));
    }

    @Operation(summary = "알림 이력 요약",
            description = """
                    알림 이력 화면의 요약 카드 숫자를 줍니다. 목록과 같은 category·period·keyword 조건을 받습니다
                    (result는 받지 않습니다 - 카드가 결과별 숫자입니다).

                    - total = delivered + failed + notSent
                    - delivered는 문자 대체 발송을 **포함**합니다. smsFallback은 그중 건수입니다(카드 보조 문구 "문자 대체 발송 N건 포함")
                    - notSent는 실패가 아닙니다(이용 제한 계정·수신자가 채널을 꺼 둔 경우)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요약 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 category·period 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/summary")
    public ApiResponse<AdminNotificationSummaryResponse> getSummary(
            @Parameter(description = "카테고리 (생략 시 전체)")
            @RequestParam(required = false) AdminNotificationCategory category,

            @Parameter(description = "기간 (생략 시 LAST_7_DAYS)")
            @RequestParam(required = false) AdminNotificationPeriod period,

            @Parameter(description = "피보호자 또는 수신자 이름 검색어 (부분일치)")
            @RequestParam(required = false) String keyword) {

        return ApiResponse.ok(adminNotificationService.getSummary(category, period, keyword));
    }
}
