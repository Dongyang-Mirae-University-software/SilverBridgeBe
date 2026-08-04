package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * 보호자 화면의 피보호자 카드 하나 — 피보호자 정보 + 오늘 복약 일정 + 복용 카운트.
 *
 * <p>나이는 생년월일 원본 대신 <b>서버가 계산한 만 나이</b>만 준다(필요 이상의 개인정보를 내리지 않는다).
 * 생년월일이 없는 계정은 {@code null}이며, 이 경우 프론트는 나이 표기를 생략한다.</p>
 *
 * @param takenCount 오늘 복용 체크된 약 수 (화면의 "오늘 2/3회 복용"의 앞 숫자)
 * @param totalCount 오늘 복용해야 할 약 수 (뒤 숫자)
 */
@Schema(description = "보호자용 피보호자별 복약 현황")
public record WardMedicationSummary(

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "피보호자 이름", example = "김영희")
        String wardName,

        @Schema(description = "만 나이 (생년월일 미등록 시 null)", example = "78")
        Integer age,

        @Schema(description = "복약 알림 ON/OFF", example = "true")
        boolean alarmEnabled,

        @Schema(description = "조회 기준일 (KST)", example = "2026-08-04")
        LocalDate doseDate,

        @Schema(description = "오늘 복용 체크된 약 수", example = "2")
        int takenCount,

        @Schema(description = "오늘 복용해야 할 약 수", example = "3")
        int totalCount,

        @Schema(description = "오늘의 복약 일정 (복용 시각 순)")
        List<MedicationItem> medications
) {
    public static WardMedicationSummary of(String wardId, String wardName, Integer age, boolean alarmEnabled,
                                           LocalDate doseDate, List<MedicationItem> medications) {
        int takenCount = (int) medications.stream().filter(MedicationItem::taken).count();
        return new WardMedicationSummary(
                wardId, wardName, age, alarmEnabled, doseDate, takenCount, medications.size(), medications);
    }
}
