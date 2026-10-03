package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * 카메라의 가장 최근 AI 분석 결과(화면 표시용). 이상감지 판정·알림과는 별개다 - 알림은 {@code anomaly-detected}로만 간다.
 *
 * <p>값은 이상감지 수신기가 AI WebSocket으로 받아 둔 마지막 결과다({@link
 * kr.silverbridge.main.domain.camera.service.LiveAnalysisSnapshotPort}).</p>
 */
@Schema(description = "최근 AI 분석 결과")
public record LiveAnalysisSnapshot(
        @Schema(description = "감지 종류(FIRE·NORMAL·UNKNOWN 등, 연기는 FIRE)", example = "NORMAL")
        String detectedType,
        @Schema(description = "표시 문구", example = "정상")
        String detectedTypeLabel,
        @Schema(description = "AI 신뢰도(0~1)", example = "0.12")
        double confidence,
        @Schema(description = "AI 위험 판정", example = "false")
        boolean danger,
        @Schema(description = "AI 분석 시각. AI가 보내지 않으면 null", nullable = true)
        OffsetDateTime analyzedAt
) {}
