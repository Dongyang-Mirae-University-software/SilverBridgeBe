package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;

import java.time.OffsetDateTime;

/**
 * 이상감지 영상 클립 한 건(보호자·피보호자 공용).
 *
 * <p>파일 경로·카메라 세션·소유자 정보는 싣지 않는다 - 재생은 {@code clipId}로 파일 API를 부른다. AI 헤더에서 온 값
 * ({@code durationMs}·{@code width}·{@code height})은 AI가 주지 않았으면 null이다(0으로 채우지 않는다).</p>
 */
@Schema(description = "이상감지 영상 클립")
public record AnomalyClipItem(

        @Schema(description = "클립 ID (파일 API 경로에 사용)", example = "101")
        Long clipId,

        @Schema(description = "상황 ID", example = "37")
        Long incidentId,

        @Schema(description = "감지 시각 (클립은 이 시각 앞 3초 + 뒤 2초)")
        OffsetDateTime detectedAt,

        @Schema(description = "영상 길이(ms). AI가 주지 않았으면 null", example = "5000")
        Integer durationMs,

        @Schema(description = "가로 픽셀. 모르면 null", example = "1920")
        Integer width,

        @Schema(description = "세로 픽셀. 모르면 null", example = "1080")
        Integer height,

        @Schema(description = "파일 크기(바이트)", example = "1900000")
        long sizeBytes,

        @Schema(description = "저장 시각")
        OffsetDateTime createdAt
) {
    public static AnomalyClipItem of(AnomalyClip clip) {
        return new AnomalyClipItem(
                clip.getId(),
                clip.getIncidentId(),
                clip.getDetectedAt(),
                clip.getDurationMs(),
                clip.getWidth(),
                clip.getHeight(),
                clip.getSizeBytes(),
                clip.getCreatedAt());
    }
}
