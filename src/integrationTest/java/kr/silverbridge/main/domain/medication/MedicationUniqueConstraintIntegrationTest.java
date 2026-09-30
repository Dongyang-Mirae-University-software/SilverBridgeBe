package kr.silverbridge.main.domain.medication;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationMissedAlertLog;
import kr.silverbridge.main.domain.medication.entity.MedicationReminderLog;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationMissedAlertLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationReminderLogRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 복약 알림 3개 테이블의 UNIQUE 제약이 실제 DB에서 지켜지는가.
 *
 * <p>목(mock) 테스트는 리포지토리 메서드 시그니처만 검증하지 제약 자체는 실행하지 않는다. 세 제약 모두
 * "선점 후 발송"·"체크는 멱등" 같은 도메인 불변 규칙의 최종 방어선이라({@code .claude/rules/domain-security-policy.md}
 * 복약 알림 불변 규칙 ④·⑨, IDOR 규칙과 별개로) 실제 PostgreSQL에서 위반 시 저장이 막히는지 고정해 둔다.</p>
 *
 * <p>PostgreSQL은 제약 위반 뒤 트랜잭션이 중단(aborted) 상태가 되어 같은 트랜잭션에서 추가 쿼리를 하면
 * "current transaction is aborted" 오류가 난다. 그래서 각 위반 검증은 그 테스트의 마지막 동작이다.</p>
 */
class MedicationUniqueConstraintIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private MedicationIntakeRepository medicationIntakeRepository;
    @Autowired private MedicationReminderLogRepository medicationReminderLogRepository;
    @Autowired private MedicationMissedAlertLogRepository medicationMissedAlertLogRepository;

    @Test
    @DisplayName("medication_intake: 같은 약·같은 날짜 두 번째 체크는 DataIntegrityViolationException")
    void 복용_체크는_약과_날짜로_멱등() {
        userRepository.save(TestData.user("WMI001", "피보호자", Role.WARD));
        userRepository.save(TestData.user("GMI001", "보호자", Role.GUARDIAN));
        Medication medication = medicationRepository.saveAndFlush(
                TestData.medication("WMI001", "GMI001", "혈압약"));
        LocalDate doseDate = LocalDate.of(2026, 9, 30);

        medicationIntakeRepository.saveAndFlush(
                MedicationIntake.of(medication.getId(), doseDate, OffsetDateTime.now()));

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                medicationIntakeRepository.saveAndFlush(
                        MedicationIntake.of(medication.getId(), doseDate, OffsetDateTime.now())));
    }

    @Test
    @DisplayName("medication_reminder_log: 같은 attempt 중복은 거부되고, attempt가 다르면 둘 다 저장된다")
    void 발송_기록은_회차별로_한_번만() {
        userRepository.save(TestData.user("WMR001", "피보호자", Role.WARD));
        userRepository.save(TestData.user("GMR001", "보호자", Role.GUARDIAN));
        Medication medication = medicationRepository.saveAndFlush(
                TestData.medication("WMR001", "GMR001", "혈압약"));
        LocalDate doseDate = LocalDate.of(2026, 9, 30);

        medicationReminderLogRepository.saveAndFlush(
                MedicationReminderLog.of(medication.getId(), doseDate, MedicationReminderLog.ATTEMPT_FIRST,
                        OffsetDateTime.now()));

        // attempt가 다르면(재알림) 같은 약·날짜라도 허용된다
        assertThatNoException().isThrownBy(() ->
                medicationReminderLogRepository.saveAndFlush(
                        MedicationReminderLog.of(medication.getId(), doseDate, MedicationReminderLog.ATTEMPT_RETRY,
                                OffsetDateTime.now())));

        // 같은 attempt(=1) 중복은 거부된다 - 이 테스트의 마지막 동작(트랜잭션이 중단 상태가 되므로)
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                medicationReminderLogRepository.saveAndFlush(
                        MedicationReminderLog.of(medication.getId(), doseDate, MedicationReminderLog.ATTEMPT_FIRST,
                                OffsetDateTime.now())));
    }

    @Test
    @DisplayName("medication_missed_alert_log: 같은 (보호자, 피보호자, 날짜) 중복 발송 기록은 거부된다")
    void 미복용_요약은_보호자당_하루_한_건() {
        userRepository.save(TestData.user("WMM001", "피보호자", Role.WARD));
        userRepository.save(TestData.user("GMM001", "보호자", Role.GUARDIAN));
        LocalDate doseDate = LocalDate.of(2026, 9, 30);

        medicationMissedAlertLogRepository.saveAndFlush(
                MedicationMissedAlertLog.of("GMM001", "WMM001", doseDate, 1, 3, OffsetDateTime.now()));

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                medicationMissedAlertLogRepository.saveAndFlush(
                        MedicationMissedAlertLog.of("GMM001", "WMM001", doseDate, 2, 3, OffsetDateTime.now())));
    }
}
