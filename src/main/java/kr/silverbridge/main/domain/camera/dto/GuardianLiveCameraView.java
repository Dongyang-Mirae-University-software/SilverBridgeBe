package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 보호자 "실시간 카메라 보기" 목록 1건 - 등록 카메라(방 이름) + AI 송출 상태.
 *
 * <p>{@code status}가 {@code null}이면 "꺼짐"이 아니라 <b>"지금 확인할 수 없음"</b>(AI 서버 장애)이다.
 * 모르는 값을 꺼짐으로 채우면 우리 쪽 장애가 현장 카메라 전멸로 보인다(관리자 대시보드와 같은 원칙).</p>
 */
@Schema(description = "실시간 카메라 목록 항목")
public record GuardianLiveCameraView(
        @Schema(description = "카메라 세션 ID(영상·상태 API 경로에 사용)", example = "ward_a9cC5f_k3m9Q2")
        String sessionId,
        @Schema(description = "피보호자 ID", example = "a9cC5f")
        String wardId,
        @Schema(description = "피보호자 이름", example = "남궁명진")
        String wardName,
        @Schema(description = "설치 위치(방 이름)", example = "거실")
        String label,
        @Schema(description = "송출 상태: running(송출 중) / disconnected(10초 이상 프레임 없음) / offline(송출 안 함). "
                + "null = AI 서버 장애로 확인 불가", example = "running", nullable = true)
        String status,
        @Schema(description = "마지막 프레임 수신 시각", nullable = true)
        OffsetDateTime lastFrameAt
) {}
