package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.camera.entity.Camera;

import java.time.OffsetDateTime;

/**
 * 피보호자용 카메라 응답. 등록 결과 + 본인 목록 공용.
 *
 * <p>{@code sessionId}/{@code deviceId}는 AI 송출 시 각각 sessionId/cameraIdentifier로 사용한다.
 * {@code recommendedFps}는 서버가 소유한 권장 송출 프레임레이트(FE 매직상수 방지) — application.yaml {@code camera.recommended-fps}.</p>
 */
@Schema(description = "카메라 응답 (피보호자)")
public record CameraResponse(

        @Schema(description = "카메라 ID", example = "1")
        Long id,

        @Schema(description = "카메라 고유 SessionID (AI sessionId)", example = "ward_a9cC5f_k3m9Q2")
        String sessionId,

        @Schema(description = "기기 토큰 (AI cameraIdentifier · FE localStorage 저장값)", example = "dev_7Qs4Xu9Ld2")
        String deviceId,

        @Schema(description = "설치 위치(방 이름)", example = "거실")
        String label,

        @Schema(description = "사용 여부")
        boolean isActive,

        @Schema(description = "권장 송출 프레임레이트(fps)", example = "5")
        int recommendedFps,

        @Schema(description = "등록 일시", example = "2026-07-09T10:00:00+09:00")
        OffsetDateTime createdAt
) {
    public static CameraResponse of(Camera camera, int recommendedFps) {
        return new CameraResponse(
                camera.getId(),
                camera.getSessionId(),
                camera.getDeviceId(),
                camera.getLabel(),
                camera.isActive(),
                recommendedFps,
                camera.getCreatedAt()
        );
    }
}
