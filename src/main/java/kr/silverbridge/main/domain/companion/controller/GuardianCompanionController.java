package kr.silverbridge.main.domain.companion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.TodayResponse;
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
                    - 대답 원문·받아 적은 글은 없습니다(저장하지 않음). current.summary는 지금 항상 null - 숨기세요

                    [오류 code] COMPANION_NOT_AUTHORIZED(403, 연결되지 않은 피보호자)
                    """)
    @GetMapping("/api/guardian/ward/{wardId}/companion/today")
    public ResponseEntity<ApiResponse<TodayResponse>> today(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionGuardianService.today(guardianId, wardId)));
    }
}
