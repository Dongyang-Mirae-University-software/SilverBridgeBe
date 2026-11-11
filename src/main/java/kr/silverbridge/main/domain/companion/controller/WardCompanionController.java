package kr.silverbridge.main.domain.companion.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ReplyRequest;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.ResultResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.SessionResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TestSlotRequest;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TodayResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TranscriptConsentRequest;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TranscriptConsentResponse;
import kr.silverbridge.main.domain.companion.dto.CompanionDtos.TurnResponse;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.companion.service.CompanionTranscriptService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final CompanionTranscriptService transcriptService;

    @Operation(summary = "오늘 안부 대화 현황",
            description = "5개 시간대(09·12·15·18·21시)의 상태와 지금 열 수 있는 시간대를 돌려줍니다. "
                    + "mood가 null이면 '확인할 수 없어요'로 표시합니다(0·정상으로 바꾸지 않음).")
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<TodayResponse>> today(@AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.today(wardId)));
    }

    private static final String CONSENT_DESC = """
            안부 대화 내용(말벗이 한 말·어르신 대답, 음성이면 받아 적은 글)을 저장해 연결된 보호자에게 보여 줄지 정합니다.
            - 기본은 동의 안 함입니다. 동의하지 않으면 대화 내용은 저장하지 않고, 보호자에게는 기분만 보입니다.
            - 동의한 뒤의 대화부터 저장합니다(지난 대화는 채우지 않음). 저장한 내용은 30일(retentionDays) 뒤 지웁니다.
            - 철회(consent=false)하면 이미 저장한 대화 내용을 바로 지웁니다. 기분 기록은 남습니다.
            - 말벗이 꺼져 있어도 쓸 수 있습니다(철회는 언제나 가능해야 함).
            근거: 개인정보 보호법 제23조(건강 등 민감정보 별도 동의)·제21조(보유기간이 지나면 파기).
            """;

    @Operation(summary = "대화 내용 저장 동의 조회", description = CONSENT_DESC)
    @GetMapping("/transcript-consent")
    public ResponseEntity<ApiResponse<TranscriptConsentResponse>> getTranscriptConsent(
            @AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(transcriptService.getConsent(wardId)));
    }

    @Operation(summary = "대화 내용 저장 동의·철회", description = CONSENT_DESC + """

            [오류 code] INVALID_INPUT(400, consent 없음)
            """)
    @PutMapping("/transcript-consent")
    public ResponseEntity<ApiResponse<TranscriptConsentResponse>> setTranscriptConsent(
            @AuthenticationPrincipal String wardId, @RequestBody TranscriptConsentRequest request) {
        if (request == null || request.consent() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(transcriptService.setConsent(wardId, request.consent())));
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

    @Operation(summary = "[테스트 계정] 시간대를 골라 안부 대화 열기",
            description = """
                    최종 QA 테스트 계정(서버 설정 COMPANION_TEST_WARD_IDS)만 씁니다. 지금 시각과 상관없이 고른 시간대의
                    오늘 질문으로 대화를 엽니다(60분 창 없음, 몇 번이든 다시). 같은 시간대에 진행 중인 테스트 대화가 있으면
                    그 대화를 이어 갑니다(resumed=true). 테스트 대화도 일반 대화와 똑같이 저장·집계됩니다.
                    테스트 계정의 턴·마치기 응답에는 analysis(AI 판정 결과, 저장 안 함)가 함께 옵니다.

                    [오류 code]
                    COMPANION_TEST_NOT_ALLOWED(403, 테스트 계정 아님) / COMPANION_SLOT_INVALID(400, 시간대 형식 오류) /
                    COMPANION_TURN_IN_PROGRESS(409, 같은 대화를 여는 중)
                    """ + COMMON_ERRORS)
    @PostMapping("/test/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> openTest(
            @AuthenticationPrincipal String wardId, @RequestBody(required = false) TestSlotRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(companionService.openTest(wardId, request == null ? null : request.slot())));
    }

    @Operation(summary = "[테스트 계정] 안부 말벗 안내 푸시 보내기",
            description = """
                    고른 시간대의 질문 안내(COMPANION_PROMPT)를 내 기기로 바로 보냅니다. 정각 안내와 같은 문구·채널(FCM)이고
                    data에 test=true가 더해집니다(slot 포함). 응답률 집계에는 잡히지 않습니다. 분당 3회·시간당 30회.
                    204는 발송을 요청했다는 뜻입니다 - 정지 계정·알림 설정 끔·FCM 토큰 없음이면 정각 안내와 똑같이
                    보내지지 않고 응답은 그대로 204입니다(결과는 알림 이력에서 확인).

                    [오류 code]
                    COMPANION_TEST_NOT_ALLOWED(403) / COMPANION_SLOT_INVALID(400) / TOO_MANY_REQUESTS(429, 테스트 안내 분 3·시간 30회)
                    """ + COMMON_ERRORS)
    @PostMapping("/test/prompt")
    public ResponseEntity<Void> sendTestPrompt(
            @AuthenticationPrincipal String wardId, @RequestBody(required = false) TestSlotRequest request) {
        companionService.sendTestPrompt(wardId, request == null ? null : request.slot());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "글로 대답하기",
            description = """
                    대답 글(1~500자)을 보내고 말벗 응답 문장을 받습니다. 대답·응답 문장은 대화 내용 저장에 동의한 경우에만 저장하고 30일 뒤 지웁니다.
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
                    응답의 transcript는 받아 적은 글이며 대화 내용 저장에 동의한 경우에만 저장합니다(30일 뒤 삭제). 음성·사진은 저장하지 않습니다.

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
