package kr.silverbridge.main.domain.checkin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest;
import kr.silverbridge.main.domain.checkin.service.CheckinQuestionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
