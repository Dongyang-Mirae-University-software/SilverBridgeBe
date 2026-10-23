package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 디바이스 관리 상단 카드.
 *
 * <p>모르는 값은 0이 아니라 {@code null}이다 - AI 연결이 끊기면 {@code live}·{@code offline}이 null이고,
 * 등록 상한이 꺼져 있으면 {@code maxTotal}이 null이다(0이면 "한 대도 못 단다"로 읽힌다).</p>
 */
@Schema(description = "관리자 디바이스 관리 요약")
public record AdminCameraSummaryResponse(

        @Schema(description = "AI 수신기 연결 여부(첫 세션 목록까지 받은 상태). false면 live·offline은 null", example = "true")
        boolean aiConnected,

        @Schema(description = "등록 카메라 수(탈퇴 처리 중인 피보호자의 카메라 제외)", example = "3")
        long registered,

        @Schema(description = "서비스 전체 등록 상한(camera.max-total). 상한이 꺼져 있으면 null", example = "3", nullable = true)
        Integer maxTotal,

        @Schema(description = "정상 작동(LIVE) 수. 확인 불가면 null", example = "2", nullable = true)
        Long live,

        @Schema(description = "연결이 끊긴(OFFLINE) 수. 확인 불가면 null. live + offline = registered", example = "1", nullable = true)
        Long offline,

        @Schema(description = "이용 중인 피보호자 수(카드의 '등록 N / 피보호자 M')", example = "34")
        long totalWards,

        @Schema(description = "카메라를 한 대도 등록하지 않은 이용 중 피보호자 수(대시보드와 같은 계산)", example = "31")
        long wardsWithoutCamera
) {
}
