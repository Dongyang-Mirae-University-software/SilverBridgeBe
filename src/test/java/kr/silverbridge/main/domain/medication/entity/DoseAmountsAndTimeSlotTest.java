package kr.silverbridge.main.domain.medication.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 복용량 0.5 단위 규칙·응답 정규화·문구, 시각 → 시간대 경계(2026-10-22). */
class DoseAmountsAndTimeSlotTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1", "1.0", "1.5", "2", "98.5", "99", "0.50"})
    @DisplayName("0.5 단위·0.5~99 는 유효")
    void valid(String amount) {
        assertThat(DoseAmounts.isValid(new BigDecimal(amount))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3", "0", "-1", "0.25", "1.2", "99.5", "100"})
    @DisplayName("0.5 배수가 아니거나 범위 밖이면 무효")
    void invalid(String amount) {
        assertThat(DoseAmounts.isValid(new BigDecimal(amount))).isFalse();
        assertThat(DoseAmounts.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1.0,1", "0.5,0.5", "10.0,10", "2.50,2.5", "99,99"})
    @DisplayName("응답 정규화: 정수는 소수점 없이(1.0 → 1, 지수 표기 없음), 반 알은 0.5")
    void normalize(String stored, String expected) {
        assertThat(DoseAmounts.normalize(new BigDecimal(stored)).toString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0.5,반 알", "1.0,1정", "2,2정", "1.5,1정 반", "10.0,10정"})
    @DisplayName("서버 문구: 0.5 = 반 알, 1.5 = 1정 반")
    void label(String amount, String expected) {
        assertThat(DoseAmounts.label(new BigDecimal(amount))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "04:00,MORNING", "08:00,MORNING", "10:59,MORNING",
            "11:00,LUNCH", "13:00,LUNCH", "15:59,LUNCH",
            "16:00,DINNER", "18:00,DINNER", "20:59,DINNER",
            "21:00,BEDTIME", "22:00,BEDTIME", "23:59,BEDTIME", "00:00,BEDTIME", "03:59,BEDTIME"})
    @DisplayName("시각 → 시간대 경계: 04~10 아침 / 11~15 점심 / 16~20 저녁 / 그 외 취침 전")
    void fromDoseTime(String time, MedicationTimeSlot expected) {
        assertThat(MedicationTimeSlot.fromDoseTime(LocalTime.parse(time))).isEqualTo(expected);
    }

    @Test
    @DisplayName("각 시간대의 기본 시각은 자기 시간대로 돌아온다(단건 등록 기본값과 어긋나지 않음)")
    void defaultTimesRoundTrip() {
        for (MedicationTimeSlot slot : MedicationTimeSlot.values()) {
            assertThat(MedicationTimeSlot.fromDoseTime(slot.defaultTime())).isEqualTo(slot);
        }
    }
}
