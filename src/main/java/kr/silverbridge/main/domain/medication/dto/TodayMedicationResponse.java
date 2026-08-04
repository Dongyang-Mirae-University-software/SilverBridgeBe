package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * 피보호자 화면의 "오늘의 복약 일정" — 목록 + 완료 카운트("0/3회 완료").
 */
@Schema(description = "피보호자용 오늘의 복약 일정")
public record TodayMedicationResponse(

        @Schema(description = "조회 기준일 (KST)", example = "2026-08-04")
        LocalDate doseDate,

        @Schema(description = "복용 체크한 약 수", example = "0")
        int takenCount,

        @Schema(description = "오늘 복용해야 할 약 수", example = "3")
        int totalCount,

        @Schema(description = "오늘의 복약 일정 (복용 시각 순)")
        List<MedicationItem> medications
) {
    public static TodayMedicationResponse of(LocalDate doseDate, List<MedicationItem> medications) {
        int takenCount = (int) medications.stream().filter(MedicationItem::taken).count();
        return new TodayMedicationResponse(doseDate, takenCount, medications.size(), medications);
    }
}
