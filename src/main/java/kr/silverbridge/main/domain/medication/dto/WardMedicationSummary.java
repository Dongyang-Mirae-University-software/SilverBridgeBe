package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.service.GuardianMissedAlertSetting;
import kr.silverbridge.main.domain.medication.service.MedicationPreference;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 보호자 화면의 피보호자 카드 하나 — 피보호자 정보 + 오늘 복약 일정 + 복용 카운트 + 알림 설정.
 *
 * <p>나이는 생년월일 원본 대신 <b>서버가 계산한 만 나이</b>만 준다(필요 이상의 개인정보를 내리지 않는다).
 * 생년월일이 없는 계정은 {@code null}이며, 이 경우 프론트는 나이 표기를 생략한다.</p>
 *
 * <p><b>설정 4종을 함께 싣는 이유</b>: 카드마다 토글·시각 피커를 그리는데, 이 값들이 없으면 프론트가
 * 피보호자 수만큼 설정 API를 더 호출해야 한다. 축은 서로 다르다 — {@code alarmEnabled}·
 * {@code remindAgainEnabled}는 피보호자 계정에 붙어 보호자들이 공유하고,
 * {@code missedAlert*}는 보호자 본인 것이라 다른 보호자와 값이 다를 수 있다.</p>
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

        @Schema(description = "복약 알림 ON/OFF (피보호자에게 보낼 복용 시각 알림)", example = "true")
        boolean alarmEnabled,

        @Schema(description = "재알림 ON/OFF (최초 알림 15분 뒤 한 번 더)", example = "true")
        boolean remindAgainEnabled,

        @Schema(description = "이 피보호자 건 미복용 요약을 내가 받을지", example = "true")
        boolean missedAlertEnabled,

        @Schema(description = "이 피보호자 건 요약을 받을 시각(KST). 이 시각까지 예정된 약만 집계됩니다.",
                example = "21:00:00", type = "string")
        LocalTime missedAlertTime,

        @Schema(description = "조회 기준일 (KST)", example = "2026-08-04")
        LocalDate doseDate,

        @Schema(description = "오늘 복용 체크된 약 수", example = "2")
        int takenCount,

        @Schema(description = "오늘 복용해야 할 약 수", example = "3")
        int totalCount,

        @Schema(description = "오늘의 복약 일정 (복용 시각 순)")
        List<MedicationItem> medications
) {
    public static WardMedicationSummary of(String wardId, String wardName, Integer age,
                                           MedicationPreference preference,
                                           GuardianMissedAlertSetting missedAlert,
                                           LocalDate doseDate, List<MedicationItem> medications) {
        int takenCount = (int) medications.stream().filter(MedicationItem::taken).count();
        return new WardMedicationSummary(
                wardId, wardName, age,
                preference.alarmEnabled(), preference.remindAgainEnabled(),
                missedAlert.enabled(), missedAlert.alertTime(),
                doseDate, takenCount, medications.size(), medications);
    }
}
