package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.camera.entity.Camera;

/**
 * 보호자용 카메라 뷰(allowlist 항목). ACTIVE 연결 피보호자들의 활성 카메라만 노출한다.
 *
 * <p>FE는 이 {@code sessionId} 집합으로 AI {@code live-streams}를 필터/구독한다. 카드 표기는 {@code wardName · label}(예 "남궁명진 · 거실").
 * 민감·시스템 필드(deviceId·registeredBy 등)는 보호자에게 노출하지 않는다.</p>
 */
@Schema(description = "카메라 뷰 (보호자 — 연결된 피보호자의 활성 카메라)")
public record GuardianCameraView(

        @Schema(description = "카메라 고유 SessionID (AI 세션 구독 키)", example = "ward_a9cC5f_k3m9Q2")
        String sessionId,

        @Schema(description = "피보호자 ID", example = "a9cC5f")
        String wardId,

        @Schema(description = "피보호자 이름", example = "남궁명진")
        String wardName,

        @Schema(description = "설치 위치(방 이름)", example = "거실")
        String label,

        @Schema(description = "사용 여부")
        boolean isActive
) {
    public static GuardianCameraView of(Camera camera, String wardName) {
        return new GuardianCameraView(
                camera.getSessionId(),
                camera.getWardId(),
                wardName,
                camera.getLabel(),
                camera.isActive()
        );
    }
}
