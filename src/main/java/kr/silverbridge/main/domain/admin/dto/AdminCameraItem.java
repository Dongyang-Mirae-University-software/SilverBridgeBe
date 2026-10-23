package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 디바이스 관리 목록의 한 행.
 *
 * <p><b>싣지 않는 것</b>: 송출 기기 토큰({@code deviceId} - 재등록 키라 알면 그 자리에 카메라를 다시 등록할 수 있다),
 * 영상·정지 화면·클립 주소(관리자 열람 금지 - {@code AdminAnomalyClipExposureTest}와 같은 취지), 상세 주소.
 * {@code AdminCameraItemExposureTest}가 칸 목록을 고정한다.</p>
 */
@Schema(description = "관리자 디바이스 관리 - 카메라 1대")
public record AdminCameraItem(

        @Schema(description = "카메라 ID", example = "12")
        Long cameraId,

        @Schema(description = "세션 ID(AI 송출 식별값)", example = "ward_a1B2c3D4e5F6g7H8")
        String sessionId,

        @Schema(description = "설치 위치(방 이름)", example = "거실")
        String room,

        @Schema(description = "담당 피보호자 ID", example = "W00012")
        String wardId,

        @Schema(description = "담당 피보호자 이름. 회원 행을 찾지 못하면 null", example = "김영자")
        String wardName,

        @Schema(description = "피보호자 주소(상세 주소 제외). 회원 행을 찾지 못하면 null", example = "서울 강남구 역삼로 123")
        String wardAddress,

        @Schema(description = "사용 여부(사용자 표시용 - 꺼도 감지·알림은 계속된다)", example = "true")
        boolean isActive,

        @Schema(description = "등록 시각(KST)", example = "2026-10-14T13:05:00+09:00")
        OffsetDateTime registeredAt,

        @Schema(description = "LIVE·OFFLINE. AI 연결이 끊겨 확인할 수 없으면 null", example = "LIVE", nullable = true)
        AdminCameraLiveStatus liveStatus
) {
}
