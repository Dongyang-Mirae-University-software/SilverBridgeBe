package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncidentFeedback;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;

import java.time.OffsetDateTime;

/**
 * 보호자 한 명의 응답. 관리자가 엇갈린 판정을 정리할 때 근거가 된다.
 *
 * @param respondedAt <b>마지막으로 답을 낸 시각</b>이다(처음 답한 시각이 아니다). 보호자는 응답을
 *                    번복할 수 있으므로, 관리자에게 필요한 것은 "지금 이 사람의 의견이 언제 것인가"다.
 *                    처음 시각을 주면 번복 뒤에도 옛 시각이 남아 판단을 그르친다.
 */
@Schema(description = "보호자 응답 한 건")
public record AdminAnomalyFeedbackItem(

        @Schema(description = "보호자 ID", example = "X9Y8Z7")
        String guardianId,

        @Schema(description = "보호자 이름 (탈퇴 시 null)", example = "김철수")
        String guardianName,

        @Schema(description = "응답", example = "REAL", allowableValues = {"REAL", "FALSE_ALARM"})
        AnomalyVerdict verdict,

        @Schema(description = "마지막으로 답한 시각")
        OffsetDateTime respondedAt
) {
    public static AdminAnomalyFeedbackItem of(AnomalyIncidentFeedback feedback, String guardianName) {
        return new AdminAnomalyFeedbackItem(
                feedback.getGuardianId(),
                guardianName,
                feedback.getVerdict(),
                feedback.getUpdatedAt()
        );
    }
}
