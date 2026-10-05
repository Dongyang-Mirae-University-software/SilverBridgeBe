package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 피보호자 "내 카메라" 목록 1건 - 본인 등록 카메라 + AI 송출 상태.
 *
 * <p>{@code status}가 {@code null}이면 "연결 안 됨"이 아니라 <b>"지금 확인할 수 없음"</b>(AI 서버 장애)이다.
 * 보호자 실시간 목록({@link GuardianLiveCameraView})과 같은 값·같은 원칙이다(모르는 값을 꺼짐으로 채우지 않는다).</p>
 */
@Schema(description = "내 카메라 목록 항목 (송출 상태 포함)")
public record WardLiveCameraView(
        @Schema(description = "카메라 ID(수정·삭제 경로에 사용)", example = "1")
        Long id,
        @Schema(description = "카메라 세션 ID", example = "ward_a9cC5f_k3m9Q2")
        String sessionId,
        @Schema(description = "기기 토큰 - 지금 기기의 localStorage 값과 같으면 \"이 기기\"", example = "dev_7Qs4Xu9Ld2")
        String deviceId,
        @Schema(description = "설치 위치(방 이름)", example = "거실")
        String label,
        @Schema(description = "송출 상태: running(연결됨) / disconnected(10초 이상 프레임 없음) / offline(송출 안 함). "
                + "null = AI 서버 장애로 확인 불가(화면은 \"확인 중\")", example = "running", nullable = true)
        String status,
        @Schema(description = "마지막 프레임 수신 시각", nullable = true)
        OffsetDateTime lastFrameAt,
        @Schema(description = "등록 일시", example = "2026-10-05T10:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static WardLiveCameraView of(CameraResponse camera, String status, OffsetDateTime lastFrameAt) {
        return new WardLiveCameraView(camera.id(), camera.sessionId(), camera.deviceId(), camera.label(),
                status, lastFrameAt, camera.createdAt());
    }
}
