package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미복용 요약에서 "오늘 늦게 등록한 약"을 빼는 규칙(MED-G14)의 경계값 테스트.
 *
 * <p>규칙이 시계와 분리된 static이라 실행 시각과 무관하게 리터럴로 검증한다
 * ({@code MedicationMissedAlertPlannerTest}는 실시간 시계를 쓰므로 자정 근처에서 건너뛴다).</p>
 */
class MedicationMissedAlertRegistrationRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalTime DOSE_TIME = LocalTime.of(8, 0);

    @Test
    @DisplayName("오늘 복용 시각보다 늦게 등록 → 제외(20:50에 등록한 아침 약)")
    void 오늘_복용시각_이후_등록은_제외() {
        assertThat(rule(OffsetDateTime.of(TODAY, LocalTime.of(20, 50), KST))).isTrue();
        assertThat(rule(OffsetDateTime.of(TODAY, LocalTime.of(8, 0, 1), KST))).isTrue();
    }

    @Test
    @DisplayName("복용 시각과 같은 시각(정각)에 등록 → 센다(먹을 기회가 있었다)")
    void 정각_등록은_센다() {
        assertThat(rule(OffsetDateTime.of(TODAY, DOSE_TIME, KST))).isFalse();
    }

    @Test
    @DisplayName("오늘 복용 시각보다 일찍 등록 → 센다")
    void 복용시각_전_등록은_센다() {
        assertThat(rule(OffsetDateTime.of(TODAY, LocalTime.of(7, 30), KST))).isFalse();
    }

    @Test
    @DisplayName("어제 이전 등록분은 등록 시각과 무관하게 센다")
    void 어제_등록은_센다() {
        assertThat(rule(OffsetDateTime.of(TODAY.minusDays(1), LocalTime.of(23, 0), KST))).isFalse();
    }

    @Test
    @DisplayName("등록 시각은 KST로 바꿔 비교한다 - UTC로 저장된 값이 날짜를 넘어가도 KST 날짜로 판정")
    void UTC_저장값도_KST로_판정() {
        // UTC 2026-10-01 23:30 = KST 2026-10-02 08:30 → 오늘 복용 시각(08:00) 이후 등록
        assertThat(rule(OffsetDateTime.of(TODAY.minusDays(1), LocalTime.of(23, 30), ZoneOffset.UTC))).isTrue();
        // UTC 2026-10-02 10:00 = KST 2026-10-02 19:00 (UTC 날짜만 보면 같지만 시각 비교는 KST)
        assertThat(rule(OffsetDateTime.of(TODAY, LocalTime.of(10, 0), ZoneOffset.UTC))).isTrue();
        // UTC 2026-10-01 22:00 = KST 2026-10-02 07:00 → 복용 시각 전
        assertThat(rule(OffsetDateTime.of(TODAY.minusDays(1), LocalTime.of(22, 0), ZoneOffset.UTC))).isFalse();
    }

    @Test
    @DisplayName("등록 시각을 모르면(감사 필드 미기록) 예전처럼 센다")
    void 등록시각_없음은_센다() {
        assertThat(rule(null)).isFalse();
    }

    private static boolean rule(OffsetDateTime createdAt) {
        Medication medication = Medication.builder()
                .wardId("WD0001")
                .createdBy("GD0001")
                .name("혈압약")
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(DOSE_TIME)
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "createdAt", createdAt);
        return MedicationMissedAlertPlanner.registeredAfterDoseToday(medication, TODAY);
    }
}
