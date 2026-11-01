package kr.silverbridge.main.domain.checkin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.checkin.dto.CheckinFinishRequest;
import kr.silverbridge.main.domain.checkin.dto.CheckinFinishResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinSessionResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinWardSettingRequest;
import kr.silverbridge.main.domain.checkin.dto.CheckinWardSettingResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinTurnResponse;
import kr.silverbridge.main.domain.checkin.service.CheckinConversationService;
import kr.silverbridge.main.domain.checkin.service.CheckinSettingService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "피보호자 - 안부 말벗")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardCheckinController {

    private static final String COMMON_ERRORS = """

            [공통 오류 code]
            CHECKIN_DISABLED(503, 말벗이 꺼진 서버) / TOO_MANY_REQUESTS(429) / CHECKIN_BUSY(429, 이전 요청 처리 중) /
            CHECKIN_UNAVAILABLE(503) / CHECKIN_TIMEOUT(504)
            """;

    private final CheckinConversationService conversationService;
    private final CheckinSettingService settingService;

    @Operation(summary = "안부 대화 시작 (피보호자 전용)", description = """
            지금 시각(KST)의 안부 대화를 시작합니다. 시각은 서버가 정합니다(지금 이전의 가장 늦은 09·12·15·18·21시).
            같은 시각에 진행 중인 대화가 있으면 그 대화를 돌려줍니다(resumed=true). finishRequired=true면 정리만 남은 대화입니다.
            대화는 최대 maxTurns번·endsAt까지입니다.

            [오류 code] CHECKIN_NOT_OPEN(409, 응답 창 밖 - 시각부터 1시간) / CHECKIN_ALREADY_DONE(409, 이번 시각 대화를 이미 마침)
            """ + COMMON_ERRORS)
    @PostMapping("/api/ward/checkin/sessions")
    public ResponseEntity<ApiResponse<CheckinSessionResponse>> start(@AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(conversationService.start(wardId)));
    }

    @Operation(summary = "안부 대화 한 턴 (피보호자 전용)", description = """
            Content-Type: multipart/form-data
            - audio(필수): 어르신 말씀 녹음. webm·ogg·wav·m4a, 5MB·60초 이하.
            - frames(선택, 여러 개): 말씀하시는 동안의 얼굴 사진 JPEG, 최대 8장·장당 300KB.
              **표정 분석 동의(faceConsent)가 꺼져 있으면 서버가 버리고 AI로 보내지 않습니다.**
            응답의 reply를 읽어 드리고, finishRequired=true면 정리(finish)를 부릅니다.
            emergency·selfHarm이 true면 reply가 119·109 고정 안내입니다. 녹음과 사진은 저장하지 않습니다.

            [오류 code] CHECKIN_SESSION_NOT_FOUND(404) / CHECKIN_SESSION_NOT_OWNED(403) /
            CHECKIN_SESSION_CLOSED(409) / CHECKIN_FINISH_REQUIRED(409, 시간·턴이 끝남 - finish를 부르세요) /
            CHECKIN_SPEECH_NOT_RECOGNIZED(422, 다시 말씀해 달라고 안내) / CHECKIN_INVALID_AUDIO(400) /
            CHECKIN_AUDIO_TOO_LARGE(413) / CHECKIN_AUDIO_TOO_LONG(413) / CHECKIN_INVALID_FRAME(400)
            """ + COMMON_ERRORS)
    @PostMapping(value = "/api/ward/checkin/sessions/{sessionId}/turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CheckinTurnResponse>> turn(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "대화 시작 응답의 sessionId") @PathVariable String sessionId,
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestPart(value = "frames", required = false) List<MultipartFile> frames) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(conversationService.turn(wardId, sessionId, audio, frames)));
    }

    @Operation(summary = "안부 대화 정리 (피보호자 전용)", description = """
            대화를 마치고 결과를 저장합니다. selfReport는 본인이 고른 오늘 기분(선택)입니다.
            끝난 이유는 서버가 정합니다. 이미 정리한 대화를 다시 부르면 같은 결과를 돌려줍니다.

            [오류 code] CHECKIN_SESSION_NOT_FOUND(404) / CHECKIN_SESSION_NOT_OWNED(403) / CHECKIN_SESSION_CLOSED(409) /
            CHECKIN_INVALID_SELF_REPORT(400, UNDECIDED는 고를 수 없음)
            """ + COMMON_ERRORS)
    @PostMapping("/api/ward/checkin/sessions/{sessionId}/finish")
    public ResponseEntity<ApiResponse<CheckinFinishResponse>> finish(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "대화 시작 응답의 sessionId") @PathVariable String sessionId,
            @RequestBody(required = false) CheckinFinishRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(conversationService.finish(wardId, sessionId,
                        request == null ? null : request.selfReport())));
    }

    @Operation(summary = "말벗 설정 조회 (피보호자 전용)",
            description = "표정 분석 동의(faceConsent, 기본 false)·안부 알림 받기(promptEnabled, 기본 false - 보호자가 켬)." + COMMON_ERRORS)
    @GetMapping("/api/ward/checkin/setting")
    public ResponseEntity<ApiResponse<CheckinWardSettingResponse>> getSetting(@AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(settingService.getForWard(wardId)));
    }

    @Operation(summary = "말벗 설정 변경 (피보호자 전용)", description = """
            보낸 항목만 바꿉니다(null = 그대로).
            - faceConsent=true면 대화 중 얼굴 사진을 AI로 보내 표정을 참고합니다(현재 AI는 표정 결과를 내지 않음). false면 사진을 버립니다.
            - promptEnabled=false면 하루 다섯 번 오는 안부 알림을 멈춥니다(본인이 끌 수 있음).

            [오류 code] CHECKIN_INVALID_SETTING(400, 바꿀 항목 없음)
            """ + COMMON_ERRORS)
    @PutMapping("/api/ward/checkin/setting")
    public ResponseEntity<ApiResponse<CheckinWardSettingResponse>> updateSetting(
            @AuthenticationPrincipal String wardId, @RequestBody(required = false) CheckinWardSettingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(settingService.updateForWard(wardId, request)));
    }
}
