package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyVerdict;
import kr.silverbridge.main.global.enums.DetectedType;

import java.time.OffsetDateTime;

/**
 * 보호자 화면의 이상감지 이력 한 건(= 상황 하나).
 *
 * <p>SOS 이력과 같은 원칙으로 <b>원자값만</b> 준다 - "거실에서 화재가 4번 감지되었습니다" 같은 문구는
 * 프론트가 조립한다. 다만 {@code detectedTypeLabel}은 예외로 서버가 준다: 알림 문구와 같은 단어를 써야
 * 사용자가 같은 사건으로 인식하기 때문이다.</p>
 *
 * @param cameraLabel      감지된 카메라의 설치 위치. <b>카메라가 삭제되면 null</b>이다(상황 이력은 남는다)
 * @param eventCount       이 상황에 묶인 감지 횟수. "몇 번 잡혔는지"가 보호자 판단의 근거가 된다
 * @param maxConfidence    묶인 감지 중 최고 신뢰도(0.0~1.0). 평균이 아니라 최고값이다
 * @param myVerdict        내가 낸 응답. 아직 응답하지 않았으면 null
 * @param resolvedByAdmin  관리자가 확정한 건이면 true - 프론트는 응답 버튼을 비활성화한다(누르면 409)
 */
@Schema(description = "보호자용 이상감지 이력 항목(상황 단위)")
public record AnomalyIncidentItem(

        @Schema(description = "상황 ID", example = "37")
        Long incidentId,

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "피보호자 이름", example = "김영희")
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

        @Schema(description = "판정 상태", example = "PENDING",
                allowableValues = {"PENDING", "REAL", "FALSE_ALARM", "CONFLICTED"})
        AnomalyReviewStatus reviewStatus,

        @Schema(description = "내가 낸 응답 (미응답 시 null)", example = "FALSE_ALARM",
                allowableValues = {"REAL", "FALSE_ALARM"})
        AnomalyVerdict myVerdict,

        @Schema(description = "관리자 확정 여부 (true면 응답 변경 불가)", example = "false")
        boolean resolvedByAdmin
) {
    public static AnomalyIncidentItem of(AnomalyIncident incident, String wardName,
                                         String cameraLabel, AnomalyVerdict myVerdict) {
        return new AnomalyIncidentItem(
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
                myVerdict,
                incident.isAdminResolved()
        );
    }
}
