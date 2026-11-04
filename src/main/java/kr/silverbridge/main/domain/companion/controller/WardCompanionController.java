package kr.silverbridge.main.domain.companion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ReplyRequest;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 피보호자 안부 말벗 API(2026-11-04, PR-A). 기기 → 백엔드 → AI. WARD 전용.
 * 응답에 말벗 응답 문장·받아 적은 글이 담겨 캐시하지 않는다(저장도 하지 않는다).
 */
@Tag(name = "피보호자 - 안부 말벗")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
@RequestMapping("/api/ward/companion")
public class WardCompanionController {

    private static final String COMMON_ERRORS = """

            [공통 오류 code]
            COMPANION_DISABLED(503, 말벗 꺼짐) / TOO_MANY_REQUESTS(429, 분 20·시간 200회) /
            COMPANION_BUSY(503, 서버 전체 동시 8건 초과) / COMPANION_UNAVAILABLE(503) / COMPANION_TIMEOUT(504, 90초 초과)
            """;

    private final CompanionService companionService;

    @Operation(summary = "오늘 안부 대화 현황",
            description = "5개 시간대(09·12·15·18·21시)의 상태와 지금 열 수 있는 시간대를 돌려줍니다. "
                    + "mood가 null이면 '확인할 수 없어요'로 표시합니다(0·정상으로 바꾸지 않음).")
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<TodayResponse>> today(@AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.today(wardId)));
    }

    @Operation(summary = "안부 대화 열기",
            description = """
                    지금 시간대(시작 시각부터 60분)의 대화를 엽니다. 시간대·질문은 서버가 정합니다.
                    이미 진행 중인 대화가 있으면 그 대화를 돌려줍니다(resumed=true).

                    [오류 code]
                    COMPANION_NOT_IN_WINDOW(409) / COMPANION_SLOT_DONE(409, 이 시간대는 이미 마침) /
                    COMPANION_TURN_IN_PROGRESS(409, 같은 대화를 여는 중)
                    """ + COMMON_ERRORS)
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> open(@AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.open(wardId)));
    }

    @Operation(summary = "글로 대답하기",
            description = """
                    대답 글(1~500자)을 보내고 말벗 응답 문장을 받습니다. 대답·응답 문장은 저장하지 않습니다.
                    ended=true면 대화가 끝난 것이며 결과는 서버가 이미 저장했습니다. 처리에 최대 90초가 걸릴 수 있습니다.

                    [오류 code]
                    COMPANION_ANSWER_EMPTY(400) / COMPANION_ANSWER_TOO_LONG(400) / COMPANION_SESSION_NOT_FOUND(404) /
                    COMPANION_SESSION_NOT_OWNED(403) / COMPANION_SESSION_ENDED(409) / COMPANION_SESSION_LOST(409, 대화 끊김) /
                    COMPANION_TURN_IN_PROGRESS(409, 앞 대답 처리 중)
                    """ + COMMON_ERRORS)
    @PostMapping("/sessions/{sessionId}/reply")
    public ResponseEntity<ApiResponse<TurnResponse>> reply(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "대화 번호") @PathVariable long sessionId,
            @RequestBody ReplyRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.replyText(wardId, sessionId, request == null ? null : request.text())));
    }

    @Operation(summary = "음성으로 대답하기",
            description = """
                    multipart: audio 1개(WAV·WebM·MP3, 5MB 이하) + frame 0~5장(JPG·PNG, 장당 1MB 이하, 표정 분석용).
                    응답의 transcript는 받아 적은 글이며 저장하지 않습니다. 음성·사진도 저장하지 않습니다.

                    [오류 code]
                    COMPANION_AUDIO_INVALID(400) / COMPANION_AUDIO_TOO_LARGE(413) / COMPANION_FRAME_INVALID(400) /
                    COMPANION_FRAME_TOO_LARGE(413) / COMPANION_UPLOAD_TOO_LARGE(413, 음성·사진 합계 10MB 초과) /
                    COMPANION_STT_UNAVAILABLE(503, 글로 대답 안내) / 그 밖은 글 대답과 같음
                    """ + COMMON_ERRORS)
    @PostMapping(value = "/sessions/{sessionId}/reply/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<TurnResponse>> replyAudio(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "대화 번호") @PathVariable long sessionId,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestPart(value = "frame", required = false) List<MultipartFile> frames) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.replyAudio(wardId, sessionId, audio, frames)));
    }

    @Operation(summary = "안부 대화 마치기",
            description = """
                    대화를 끝내고 결과(종료 이유·기분·턴 수)를 받습니다. 이미 마친 대화는 저장된 결과를 그대로 돌려줍니다.
                    mood가 null이면 '확인할 수 없어요'입니다.

                    [오류 code]
                    COMPANION_SESSION_NOT_FOUND(404) / COMPANION_SESSION_NOT_OWNED(403) / COMPANION_SESSION_LOST(409) /
                    COMPANION_TURN_IN_PROGRESS(409)
                    """ + COMMON_ERRORS)
    @PostMapping("/sessions/{sessionId}/finish")
    public ResponseEntity<ApiResponse<ResultResponse>> finish(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "대화 번호") @PathVariable long sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.finish(wardId, sessionId)));
    }
}
