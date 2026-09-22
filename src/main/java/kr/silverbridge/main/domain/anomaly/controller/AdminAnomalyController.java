package kr.silverbridge.main.domain.anomaly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyPeriod;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalySummaryResponse;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyTypeFilter;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.service.AdminAnomalyService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 이상감지 로그 API(보기 전용).
 *
 * <p>보호자 응답과 판정 결과를 조회만 한다. 인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨
 * {@code @PreAuthorize}가 이중으로 담당한다(관리자 대시보드와 같은 방식).</p>
 *
 * <p><b>관리자용 판정·정정 API를 여기에 추가하지 말 것</b>(2026-09-21 폐지). 판정은 현장을 아는 보호자의
 * 다수결로만 정해지고, 동수는 보호자들이 다시 응답해 푼다.</p>
 */
@Tag(name = "관리자 - 이상감지")
@RestController
@RequestMapping("/api/admin/anomaly")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnomalyController {

    private final AdminAnomalyService adminAnomalyService;

    @Operation(summary = "이상감지 기록 목록",
            description = """
                    이상감지 기록을 상황 단위로, 최신순으로 조회합니다.

                    [보호자 조회와 다른 점]
                    연결 여부로 좁히지 않고 **전체**를 봅니다. 조회 전용이며 관리자가 판정을 바꾸는 API는 없습니다.

                    [보호자 응답 내역이 함께 옵니다]
                    feedbacks 에 누가 무엇이라고 답했는지가 들어 있습니다.
                    아무도 답하지 않았으면 **빈 배열**입니다(null 아님).

                    [판정 상태]
                    응답한 보호자의 다수결입니다. status=CONFLICTED 는 응답이 **동수**로 갈린 건입니다
                    (보호자들에게 재확인 안내가 나가며, 다시 응답하면 풀립니다).

                    [필터 - 모두 선택, 생략하면 조건 없음]
                    - period: TODAY · THIS_WEEK(월요일 시작) · THIS_MONTH · ALL. 첫 감지 시각 기준, KST
                    - type: FIRE(연기 포함) · FALL · WEAPON. 연기는 화재로 받아 따로 없습니다
                    - keyword: 피보호자 이름 또는 카메라 위치 부분일치. 탈퇴한 피보호자·삭제된 카메라는 검색되지 않습니다
                    - 잘못된 period·type 값은 400입니다(빈 목록으로 오독되지 않게)

                    [감지 지속]
                    durationMinutes = 마지막 감지 - 첫 감지(분). 한 번만 잡힌 상황은 0이며 화면에서는 "순간"으로 표시합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 period·type 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminAnomalyIncidentItem>> getIncidents(
            @Parameter(description = "판정 상태 필터 (생략 시 전체)")
            @RequestParam(required = false) AnomalyReviewStatus status,

            @Parameter(description = "피보호자 ID 필터 (생략 시 전체)")
            @RequestParam(required = false) String wardId,

            @Parameter(description = "기간 (생략 시 ALL)")
            @RequestParam(required = false) AdminAnomalyPeriod period,

            @Parameter(description = "감지 유형 (생략 시 전체)")
            @RequestParam(required = false) AdminAnomalyTypeFilter type,

            @Parameter(description = "피보호자 이름 또는 카메라 위치 검색어 (부분일치)")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(adminAnomalyService.getIncidents(status, wardId, period, type, keyword, page, size));
    }

    @Operation(summary = "이상감지 로그 집계",
            description = """
                    이상감지 로그 화면의 탭 건수·응답률·판정별 현황·AI 신뢰도를 한 번에 줍니다.
                    목록과 같은 period·type·keyword 조건을 받습니다.

                    [유형 탭 건수]
                    byType 은 **type 조건을 무시**합니다 - 탭을 골라도 탭 옆 숫자는 그대로입니다.
                    집계된 유형만 담습니다(0건 유형은 항목이 없습니다. "낙상 0건"은 안전이 아니라 모델이 없다는 뜻이라서요).
                    나머지(total·review·responseRate·aiConfidence)는 고른 type으로 좁혀 계산합니다.

                    [판정별 현황 - review]
                    pending(미판정) · real(위험) · falseAlarm(오탐) · conflicted(동수 - 보호자 재확인 대기). 네 값을 모두 줍니다.

                    [사용자 응답률 - responseRate]
                    (total - pending) / total, 0.0~1.0. total이 0이면 null입니다(0%로 표시하지 마세요).

                    [AI 신뢰도 - aiConfidence]
                    - average: 판정이 난 상황(위험 + 오탐)의 AI confidence 평균, 0.0~1.0. 화면 카드의 큰 숫자입니다
                    - basis: 분모(위험 + 오탐 건수). 미판정·동수는 뺍니다
                    basis가 0이면 average는 null입니다(0%로 표시하지 마세요).
                    카드 하위 칸(위험 판정·오탐)은 confidence가 아니라 비율입니다 - review.real / basis, review.falseAlarm / basis (합 100%).
                    "AI가 얼마나 확신했는가"이지 "맞았는가"가 아닙니다. confidence는 상황별 최고값이라
                    오래 이어진 상황일수록 커져, 프레임 평균보다 높게 나오는 경향이 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "집계 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 period·type 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/summary")
    public ApiResponse<AdminAnomalySummaryResponse> getSummary(
            @Parameter(description = "기간 (생략 시 ALL)")
            @RequestParam(required = false) AdminAnomalyPeriod period,

            @Parameter(description = "감지 유형 (생략 시 전체) - byType 탭 건수에는 적용되지 않습니다")
            @RequestParam(required = false) AdminAnomalyTypeFilter type,

            @Parameter(description = "피보호자 이름 또는 카메라 위치 검색어 (부분일치)")
            @RequestParam(required = false) String keyword) {

        return ApiResponse.ok(adminAnomalyService.getSummary(period, type, keyword));
    }
}
