package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationDisplayName;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;

import kr.silverbridge.main.domain.medication.entity.DoseAmounts;
import kr.silverbridge.main.domain.medication.service.MedicationClock;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 복약 일정 한 건. 보호자 화면·피보호자 화면이 같은 형태를 쓴다.
 *
 * <p>표시 문구("아침 08:00 · 1정 · 식후 30분")는 프론트가 조립한다 — 서버는 원자값만 준다.</p>
 *
 * @param taken   조회 기준일(오늘)에 복용 체크가 되었는지. 체크는 피보호자만 할 수 있다.
 * @param takenAt 체크한 시각. 미복용이면 null.
 * @param createdAt 약을 등록한 시각. 오늘 등록 전에 복용 시각이 지난 약은 미체크로 세지 않는다
 *                  ({@code MedicationClock.registeredAfterDose} - FE 복약 카드가 같은 판정을 한다).
 */
@Schema(description = "복약 일정 항목")
public record MedicationItem(

        @Schema(description = "약 ID", example = "12")
        Long medicationId,

        @Schema(description = "약 이름(저장된 원본 - 허가 품목명이면 용량·성분 괄호 포함). 화면 표시는 displayName", example = "다이아벡스정500밀리그램(메트포르민염산염)")
        String name,

        @Schema(description = "화면에 보일 약 이름(2026-11-19). 끝쪽 용량과 성분 괄호를 뗀 이름 - 직접 입력한 설명 괄호는 남는다", example = "다이아벡스정")
        String displayName,

        @Schema(description = "복용 시간대", example = "MORNING",
                allowableValues = {"MORNING", "LUNCH", "DINNER", "BEDTIME"})
        MedicationTimeSlot timeSlot,

        @Schema(description = "복용 시각", example = "08:00:00", type = "string")
        LocalTime doseTime,

        @Schema(description = "복용량(정). 정수는 1, 반 알은 0.5로 내린다(1.0이 되지 않는다)", example = "1")
        BigDecimal doseAmount,

        @Schema(description = "복용 안내 메모 (없으면 null)", example = "식후 30분")
        String memo,

        @Schema(description = "오늘 복용 체크 여부", example = "true")
        boolean taken,

        @Schema(description = "복용 체크 시각 (미복용이면 null)")
        OffsetDateTime takenAt,

        @Schema(description = "약을 등록한 시각(KST). 오늘(KST) 등록됐고 이 시각이 복용 시각보다 늦으면 오늘은 등록 전이라 "
                + "미체크로 세지 않는다(미복용 요약·보호자 대시보드와 같은 기준)")
        OffsetDateTime createdAt
) {
    /** @param intake 해당 날짜의 복용 체크. 없으면 {@code null}(미복용). */
    public static MedicationItem of(Medication medication, MedicationIntake intake) {
        return new MedicationItem(
                medication.getId(),
                medication.getName(),
                MedicationDisplayName.of(medication.getName()),
                medication.getTimeSlot(),
                medication.getDoseTime(),
                DoseAmounts.normalize(medication.getDoseAmount()),
                medication.getMemo(),
                intake != null,
                intake != null ? intake.getTakenAt() : null,
                MedicationClock.atKst(medication.getCreatedAt())
        );
    }
}
