package kr.silverbridge.main.domain.checkin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinGuardianSettingDto;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest;
import kr.silverbridge.main.domain.checkin.dto.CheckinWeeklyResponse;
import kr.silverbridge.main.domain.checkin.service.CheckinQuestionService;
import kr.silverbridge.main.domain.checkin.service.CheckinReportService;
import kr.silverbridge.main.domain.checkin.service.CheckinSettingService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "보호자 - 안부 말벗")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianCheckinController {

    private static final String ERRORS = """

            [오류 code]
            CHECKIN_NOT_AUTHORIZED(403, 연결되지 않은 피보호자) / CHECKIN_INVALID_QUESTION(400) /
            CHECKIN_DISABLED(503, 말벗이 꺼진 서버)
            """;

    private final CheckinQuestionService questionService;
    private final CheckinSettingService settingService;
    private final CheckinReportService reportService;

    @Operation(summary = "안부 질문 조회 (보호자 전용)", description = """
            하루 다섯 번(09·12·15·18·21시) 어르신께 처음 여쭐 질문입니다. 정하지 않은 시각은 기본 질문(custom=false)입니다.
            질문은 피보호자 단위라 같은 피보호자를 보는 보호자끼리 공유합니다.
            """ + ERRORS)
    @GetMapping("/api/guardian/ward/{wardId}/checkin/questions")
    public ResponseEntity<ApiResponse<CheckinQuestionsResponse>> getQuestions(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(questionService.get(guardianId, wardId)));
    }

    @Operation(summary = "안부 질문 변경 (보호자 전용)", description = """
            바꿀 시각만 보냅니다(나머지는 그대로). 질문은 1~200자이고, 빈 문자열이면 그 시각은 기본 질문으로 돌아갑니다.
            응답은 바뀐 뒤의 다섯 시각 전체입니다. 이미 시작된 대화의 질문은 바뀌지 않습니다.
            """ + ERRORS)
    @PutMapping("/api/guardian/ward/{wardId}/checkin/questions")
    public ResponseEntity<ApiResponse<CheckinQuestionsResponse>> updateQuestions(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId,
            @Valid @RequestBody CheckinQuestionsUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(questionService.update(guardianId, wardId, request)));
    }

    @Operation(summary = "안부 알림 받기 조회 (보호자 전용)", description = """
            피보호자에게 하루 다섯 번(09·12·15·18·21시) 안부 알림을 보내는지. 기본 false - 켜야 알림이 갑니다.
            """ + ERRORS)
    @GetMapping("/api/guardian/ward/{wardId}/checkin/setting")
    public ResponseEntity<ApiResponse<CheckinGuardianSettingDto>> getSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(settingService.getForGuardian(guardianId, wardId)));
    }

    @Operation(summary = "안부 알림 받기 변경 (보호자 전용)", description = """
            켜면 피보호자에게 시각마다(0~5분 분산) 푸시가 가고, 1시간 안에 대화하지 않으면 보호자에게 미응답 알림이 갑니다
            (보호자 알림 종류 설정 checkinMissed로 끌 수 있음). 피보호자 본인도 끌 수 있습니다.
            """ + ERRORS)
    @PutMapping("/api/guardian/ward/{wardId}/checkin/setting")
    public ResponseEntity<ApiResponse<CheckinGuardianSettingDto>> updateSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId,
            @Valid @RequestBody CheckinGuardianSettingDto request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(settingService.updateForGuardian(guardianId, wardId, request)));
    }

    @Operation(summary = "하루 안부 결과 (보호자 전용)", description = """
            시각별 상태(DONE·IN_PROGRESS·OPEN·UPCOMING·MISSED·NO_RECORD·BEFORE_CONNECTION)와 마친 대화의 정서·본인 기분·
            생활 신호·위험·확인 필요(근거 코드)·요약·근거(신호와 턴 번호)를 줍니다.
            **어르신 말씀을 받아쓴 원문은 주지 않습니다**(요약은 AI의 단정하지 않는 고정 문장).
            지금 연결이 맺어지기 전 시각은 BEFORE_CONNECTION으로 내용 없이 내려갑니다.
            date 생략 = 오늘(KST), 오늘부터 60일 전까지.

            [오류 code] CHECKIN_INVALID_DATE(400)
            """ + ERRORS)
    @GetMapping("/api/guardian/checkin")
    public ResponseEntity<ApiResponse<CheckinDailyResponse>> daily(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @RequestParam String wardId,
            @Parameter(description = "날짜(YYYY-MM-DD, 생략 시 오늘)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(reportService.daily(guardianId, wardId, date)));
    }

    @Operation(summary = "7일 안부 추세 (보호자 전용)", description = """
            endDate 포함 7일의 마친 대화 수·미응답 수·확인 필요 수·위험 여부·시각별 정서. endDate 생략 = 오늘(KST).

            [오류 code] CHECKIN_INVALID_DATE(400)
            """ + ERRORS)
    @GetMapping("/api/guardian/checkin/weekly")
    public ResponseEntity<ApiResponse<CheckinWeeklyResponse>> weekly(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @RequestParam String wardId,
            @Parameter(description = "끝 날짜(YYYY-MM-DD, 생략 시 오늘)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(reportService.weekly(guardianId, wardId, endDate)));
    }
}
