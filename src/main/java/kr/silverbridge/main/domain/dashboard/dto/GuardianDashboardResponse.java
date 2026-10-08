package kr.silverbridge.main.domain.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyIncidentItem;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;

import java.time.LocalTime;
import java.util.List;

/**
 * 보호자 대시보드 통합 응답. 백엔드가 데이터를 가진 칸(이상감지·SOS·복약)만 담는다.
 *
 * <p><b>모르는 값을 0으로 채우지 않는다</b> - 한 칸의 조회가 실패하면 그 칸은 {@code null}이고 칸 이름이
 * {@code unavailable}에 들어간다. 정서·활동(게임)·병원 예약은 백엔드에 데이터가 없어 필드 자체를 만들지 않았다.</p>
 */
@Schema(description = "보호자 대시보드 통합 응답 (이상감지·SOS·복약만. 정서·활동·병원 예약은 이 응답에 없음)")
public record GuardianDashboardResponse(

        @Schema(description = "확인이 필요한 일. 구성 칸이 하나라도 실패하면 total은 null")
        PendingActions pendingActions,

        @Schema(description = "조회 대상 피보호자 칩(ACTIVE 연결만). 연결이 없으면 빈 배열")
        List<WardChip> wards,

        @Schema(description = "이상감지 칸. 조회 실패 시 null(unavailable에 anomalyDetection)")
        AnomalyDetection anomalyDetection,

        @Schema(description = "SOS 칸. 조회 실패 시 null(unavailable에 sos). 처리 결과(해결됨)는 서버에 없다")
        Sos sos,

        @Schema(description = "복약 칸. 조회 실패 시 null(unavailable에 medication)")
        Medication medication,

        @Schema(description = "조회에 실패해 null로 내려간 칸 이름(anomalyDetection·sos·medication). 없으면 빈 배열")
        List<String> unavailable
) {

    @Schema(description = "확인이 필요한 일 = 이상감지 확인 필요(PENDING+CONFLICTED) + 오늘 복용 시각이 지났는데 체크되지 않은 약. SOS는 세지 않는다")
    public record PendingActions(
            @Schema(description = "합계. anomaly·medication 중 하나라도 null이면 null", nullable = true)
            Long total,
            @Schema(description = "이상감지 확인 필요 건수(실패 시 null)", nullable = true)
            Long anomaly,
            @Schema(description = "오늘 복용 시각이 지났는데 체크되지 않은 약 개수(실패 시 null)", nullable = true)
            Long medication
    ) {
    }

    @Schema(description = "피보호자 칩")
    public record WardChip(
            @Schema(description = "피보호자 ID") String wardId,
            @Schema(description = "피보호자 이름", nullable = true) String wardName,
            @Schema(description = "이 피보호자의 확인이 필요한 일 건수. 이상감지·복약 중 하나라도 실패하면 null", nullable = true)
            Long pendingCount
    ) {
    }

    @Schema(description = "이상감지 칸")
    public record AnomalyDetection(
            @Schema(description = "확인 필요 상황 수(PENDING + CONFLICTED)") long needsReviewCount,
            @Schema(description = "가장 최근의 확인 필요 상황 1건. 없으면 null (영상은 clip 필드/클립 API)", nullable = true)
            AnomalyIncidentItem latest
    ) {
    }

    @Schema(description = "SOS 칸")
    public record Sos(
            @Schema(description = "이번 달(KST 1일 00:00부터) SOS 건수") long thisMonthCount,
            @Schema(description = "가장 최근 SOS 1건(기간 무관). 한 번도 없으면 null", nullable = true)
            SosHistoryItem latest
    ) {
    }

    @Schema(description = "복약 칸")
    public record Medication(
            @Schema(description = "오늘 복용 시각이 지났는데 체크되지 않은 약 개수") long uncheckedCount,
            @Schema(description = "그중 복용 시각이 가장 이른 약 1건. 없으면 null", nullable = true)
            UncheckedMedication mostUrgent
    ) {
    }

    @Schema(description = "체크되지 않은 약. 체크 누락과 실제 미복용은 서버가 구분할 수 없다 - 문구는 '체크되지 않았습니다'로")
    public record UncheckedMedication(
            @Schema(description = "피보호자 ID") String wardId,
            @Schema(description = "피보호자 이름", nullable = true) String wardName,
            @Schema(description = "약 ID") Long medicationId,
            @Schema(description = "약 이름") String name,
            @Schema(description = "시간대", allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
            MedicationTimeSlot timeSlot,
            @Schema(description = "복용 시각(KST)", example = "12:30") LocalTime doseTime
    ) {
    }
}
