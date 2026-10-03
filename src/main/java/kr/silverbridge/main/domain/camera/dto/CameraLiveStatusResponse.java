package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 선택한 카메라의 송출 상태 + 최근 AI 분석(화면 첫 진입용 - 이후 변화는 STOMP {@code camera-analysis}로 온다).
 */
@Schema(description = "카메라 송출 상태")
public record CameraLiveStatusResponse(
        @Schema(description = "running / disconnected / offline", example = "running")
        String status,
        @Schema(description = "마지막 프레임 수신 시각", nullable = true)
        OffsetDateTime lastFrameAt,
        @Schema(description = "AI가 측정한 수신 fps", nullable = true, example = "1.9")
        Double fps,
        @Schema(description = "AI 분석 중 여부", example = "true")
        boolean isAnalyzing,
        @Schema(description = "최근 AI 분석. 아직 받은 결과가 없으면 null", nullable = true)
        LiveAnalysisSnapshot analysis
) {

    /** AI가 세션을 모를 때 - 송출하지 않는 카메라. */
    public static CameraLiveStatusResponse offline() {
        return new CameraLiveStatusResponse(CameraLiveStatus.OFFLINE, null, null, false, null);
    }
}
