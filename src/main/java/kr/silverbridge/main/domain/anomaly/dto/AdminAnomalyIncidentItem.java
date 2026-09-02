package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.global.enums.DetectedType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 화면의 이상감지 기록 한 건(= 상황 하나).
 *
 * <p>보호자용({@link AnomalyIncidentItem})과 두 가지가 다르다.</p>
 * <ul>
 *   <li><b>보호자 응답 내역이 통째로 붙는다</b> - 관리자가 해야 할 일은 "누가 무엇이라고 답했는지"를
 *       보고 엇갈린 건을 정리하는 것이라, 집계된 상태값만으로는 판단할 수 없다.</li>
 *   <li>내 응답({@code myVerdict})이 없다 - 관리자는 1차 판정을 하지 않는다.</li>
 * </ul>
 *
 * @param feedbacks  보호자 응답 목록. 아무도 답하지 않았으면 빈 배열이다(null이 아니다)
 * @param resolvedBy 정정한 관리자 ID. 정정 전이면 null이며, 채워져 있으면 확정된 건이다
 */
@Schema(description = "관리자용 이상감지 기록 항목(상황 단위)")
public record AdminAnomalyIncidentItem(

        @Schema(description = "상황 ID", example = "37")
        Long incidentId,

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "피보호자 이름 (탈퇴 시 null)", example = "김영희")
        String wardName,

        @Schema(description = "카메라 설치 위치 (카메라 삭제 시 null)", example = "거실")
        String cameraLabel,

        @Schema(description = "감지 종류", example = "FIRE", allowableValues = {"FIRE", "SMOKE"})
        DetectedType detectedType,

        @Schema(description = "감지 종류 표시 문구", example = "화재")
        String detectedTypeLabel,

        @Schema(description = "첫 감지 시각")
        OffsetDateTime startedAt,

        @Schema(description = "마지막 감지 시각")
        OffsetDateTime lastDetectedAt,

        @Schema(description = "묶인 감지 횟수", example = "4")
        int eventCount,

        @Schema(description = "최고 신뢰도", example = "0.87")
        double maxConfidence,

        @Schema(description = "판정 상태", example = "CONFLICTED",
                allowableValues = {"PENDING", "REAL", "FALSE_ALARM", "CONFLICTED"})
        AnomalyReviewStatus reviewStatus,

        @Schema(description = "보호자 응답 내역. 아무도 답하지 않았으면 빈 배열")
        List<AdminAnomalyFeedbackItem> feedbacks,

        @Schema(description = "정정한 관리자 ID (정정 전이면 null)", example = "AD0001")
        String resolvedBy,

        @Schema(description = "정정 시각 (정정 전이면 null)")
        OffsetDateTime resolvedAt,

        @Schema(description = "정정 사유 메모 (없으면 null)", example = "보호자 통화 확인 - 요리 연기")
        String reviewNote
) {
    public static AdminAnomalyIncidentItem of(AnomalyIncident incident, String wardName, String cameraLabel,
                                              List<AdminAnomalyFeedbackItem> feedbacks) {
        return new AdminAnomalyIncidentItem(
                incident.getId(),
                incident.getWardId(),
                wardName,
                cameraLabel,
                incident.getDetectedType(),
                DetectedTypeLabel.of(incident.getDetectedType()),
                incident.getStartedAt(),
                incident.getLastDetectedAt(),
                incident.getEventCount(),
                incident.getMaxConfidence(),
                incident.getReviewStatus(),
                feedbacks,
                incident.getResolvedBy(),
                incident.getResolvedAt(),
                incident.getReviewNote()
        );
    }
}
