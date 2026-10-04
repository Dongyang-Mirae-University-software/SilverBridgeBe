package kr.silverbridge.main.domain.anomaly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyClipItem;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 피보호자 본인의 이상감지 영상 클립 열람(2026-10-04).
 * 클래스 레벨 {@code @PreAuthorize("hasRole('WARD')")}로 WARD만 접근 가능(GUARDIAN/ADMIN 403).
 *
 * <p>조회 전용이다 - 피보호자용 판정 API를 여기에 추가하지 말 것(판정은 보호자만). ACTIVE 연결이 하나도 없으면 클립은
 * 비공개(404)다.</p>
 */
@Tag(name = "피보호자 - 이상감지 영상")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardAnomalyClipController {

    private final AnomalyClipAccessService clipAccessService;

    @Operation(summary = "본인 집 이상감지 영상 클립 목록 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    상황(incidentId)에 저장된 영상 클립을 최신순으로 반환합니다.
                    incidentId는 이상감지 알림(WebSocket anomaly-detected · FCM data)의 incidentId입니다.

                    [보이지 않는 경우]
                    - 연결된 보호자가 한 명도 없으면 404입니다(다시 연결되면 보입니다).
                    - 오탐 판정 시점까지 저장된 클립, 저장 후 30일이 지난 클립은 보이지 않습니다.

                    [재생] GET /api/ward/anomaly/clips/{clipId}/file 을 Authorization 헤더와 함께 fetch해 blob URL로 재생합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "클립 목록 (없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요 / 다른 피보호자의 기록", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이상감지 기록 없음 / 연결된 보호자 없음", content = @Content)
    })
    @GetMapping("/api/ward/anomaly/{incidentId}/clips")
    public ResponseEntity<ApiResponse<List<AnomalyClipItem>>> getClips(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "상황 ID") @PathVariable Long incidentId) {
        return ResponseEntity.ok(ApiResponse.ok(clipAccessService.wardClips(wardId, incidentId)));
    }

    @Operation(summary = "본인 집 이상감지 영상 클립 파일 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    클립 영상(video/webm)을 내려줍니다. Range 요청을 지원합니다(206). 응답은 캐시하지 않습니다.

                    [404] 없는 클립 · 비공개(오탐) · 만료(30일) · 연결된 보호자 없음
                    [403] 다른 피보호자의 클립 (오류 응답은 JSON)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "video/webm"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "Range 부분 응답"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요 / 다른 피보호자의 클립", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "클립 없음·비공개·만료·연결 없음", content = @Content)
    })
    @GetMapping("/api/ward/anomaly/clips/{clipId}/file")
    public ResponseEntity<Resource> getClipFile(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "클립 ID") @PathVariable Long clipId) {
        return AnomalyClipFileResponse.of(clipAccessService.wardFile(wardId, clipId));
    }
}
