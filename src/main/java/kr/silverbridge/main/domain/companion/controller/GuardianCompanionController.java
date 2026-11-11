package kr.silverbridge.main.domain.companion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.SessionDetailResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.WeekResponse;
import kr.silverbridge.main.domain.companion.service.CompanionGuardianService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 보호자 "AI 기분 체크" API(2026-11-04, PR-B). GUARDIAN 전용, 조회만. 기분 정보라 캐시하지 않는다. */
@Tag(name = "보호자 - AI 기분 체크")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianCompanionController {

    private final CompanionGuardianService companionGuardianService;

    @Operation(summary = "피보호자 오늘 안부 대화·기분 (보호자 전용)",
            description = """
                    [요청 헤더] Authorization: Bearer {accessToken}

                    연결된 피보호자의 오늘(KST) 안부 대화 요약입니다. 지금 연결이 맺어진 뒤의 대화만 보입니다.
                    - current: 오늘 가장 최근에 마친 대화의 기분. 없으면 null → "확인할 수 없어요"
                    - mood 값: JOY(기쁨)·CALM(평온)·WORRY(걱정)·DEPRESSED(우울). null은 0·정상이 아니라 "확인할 수 없어요"
                    - slots: 시간대 5개(09·12·15·18·21시)의 질문 문장·상태·기분. 시간대 값은 응답 그대로 표시하세요
                    - attention: 걱정·우울이 3일 이상 이어지면 {mood, days}, 아니면 null
                    - slots[].sessionId: 그 시간대 대화 ID('대화 보기' → sessions/{sessionId}). 대화가 없으면 null
                    - 대화 글은 이 응답에 없습니다(대화 보기에서만, 어르신 동의 시). current.summary는 지금 항상 null - 숨기세요

                    [오류 code] COMPANION_NOT_AUTHORIZED(403, 연결되지 않은 피보호자)
                    """)
    @GetMapping("/api/guardian/ward/{wardId}/companion/today")
    public ResponseEntity<ApiResponse<TodayResponse>> today(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionGuardianService.today(guardianId, wardId)));
    }

    @Operation(summary = "피보호자 최근 7일 기분 (보호자 전용)",
            description = """
                    [요청 헤더] Authorization: Bearer {accessToken}

                    오늘 포함 최근 7일의 하루 기분입니다(오래된 날부터 7개). 지금 연결이 맺어진 뒤의 대화만 셉니다.
                    - mood: 그날 마지막으로 판정된 대화의 기분 JOY·CALM·WORRY·DEPRESSED. 판정된 대화가 없으면 null("기록 없음")

                    [오류 code] COMPANION_NOT_AUTHORIZED(403, 연결되지 않은 피보호자)
                    """)
    @GetMapping("/api/guardian/ward/{wardId}/companion/week")
    public ResponseEntity<ApiResponse<WeekResponse>> week(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionGuardianService.week(guardianId, wardId)));
    }

    @Operation(summary = "피보호자 날짜별 안부 대화 (보호자 전용)",
            description = """
                    [요청 헤더] Authorization: Bearer {accessToken}

                    today와 같은 모양으로 고른 날짜의 시간대 목록을 줍니다. 최근 7일(오늘 포함)만 볼 수 있습니다.
                    - 지난 날: 대화가 없는 시간대는 MISSED, nextSlot은 null
                    - slots[].sessionId로 '대화 보기'를 엽니다

                    [오류 code] COMPANION_NOT_AUTHORIZED(403) / COMPANION_DATE_OUT_OF_RANGE(400, 7일 밖·형식 오류 YYYY-MM-DD)
                    """)
    @GetMapping("/api/guardian/ward/{wardId}/companion/days/{date}")
    public ResponseEntity<ApiResponse<TodayResponse>> day(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId,
            @Parameter(description = "KST 날짜 YYYY-MM-DD", example = "2026-11-11") @PathVariable String date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionGuardianService.day(guardianId, wardId, date)));
    }

    @Operation(summary = "피보호자 안부 대화 보기 (보호자 전용)",
            description = """
                    [요청 헤더] Authorization: Bearer {accessToken}

                    한 대화의 기분 요약·턴별 기분 흐름·나눈 이야기입니다. 최근 7일 대화만, 지금 연결 이후 대화만 볼 수 있습니다.
                    - turns[].mood: 턴 기분(항상). null = 판단할 수 없음
                    - turns[].botText·answerText: 어르신이 '대화 내용 저장'에 동의했을 때만 값이 있습니다
                    - transcript: AVAILABLE(내용 있음) / NO_CONSENT(동의 안 함·동의 전 대화 - "어르신이 대화 내용 공개에 동의하지
                      않았어요") / EXPIRED(보관 30일이 지나 지움)
                    - 저장한 대화 내용은 30일 뒤 지우고, 어르신이 동의를 철회하면 바로 지웁니다(개인정보 보호법 제21조·제23조)

                    [오류 code] COMPANION_NOT_AUTHORIZED(403) / COMPANION_SESSION_NOT_FOUND(404, 없음·다른 피보호자·연결 전 대화) /
                    COMPANION_DATE_OUT_OF_RANGE(400, 7일보다 오래된 대화)
                    """)
    @GetMapping("/api/guardian/ward/{wardId}/companion/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionDetailResponse>> session(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId,
            @Parameter(description = "대화 ID (today·days의 slots[].sessionId)") @PathVariable long sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionGuardianService.session(guardianId, wardId, sessionId)));
    }
}
