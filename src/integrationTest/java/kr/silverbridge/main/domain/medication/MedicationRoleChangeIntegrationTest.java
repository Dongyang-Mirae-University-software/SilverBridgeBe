package kr.silverbridge.main.domain.medication;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.domain.medication.service.MedicationRoleChangeService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 변경 시 복약 중지가 알림 스케줄러의 대상 조회에서 실제로 빠지는가 (2026-09-30 전체 점검 M-1).
 *
 * <p>피보호자가 보호자로 바뀐 뒤에도 그 사람 앞으로 등록된 약이 살아 있으면, 스케줄러가 복용 시각마다
 * "약 드실 시간" 알림을 계속 보낸다. 중지한 약이 스케줄러가 쓰는 바로 그 쿼리에서 빠지는지를 실제 DB로 확인한다.
 * 다른 피보호자의 약과 그 사람이 남에게 등록해 준 약은 건드리지 않아야 한다.</p>
 */
@Import(MedicationRoleChangeService.class)
class MedicationRoleChangeIntegrationTest extends PostgresIntegrationTest {

    private static final String CHANGED_ID = "WRC101";
    private static final String OTHER_WARD_ID = "WRC102";
    private static final String GUARDIAN_ID = "GRC101";

    @Autowired private MedicationRoleChangeService service;
    @Autowired private MedicationRepository medicationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("역할이 바뀐 사람 앞으로 등록된 약만 중지되고, 스케줄러의 알림 대상 조회에서 빠진다")
    void 역할변경_복약중지는_알림대상에서_빠진다() {
        userRepository.save(TestData.user(CHANGED_ID, "역할변경", Role.WARD));
        userRepository.save(TestData.user(OTHER_WARD_ID, "다른피보호자", Role.WARD));
        userRepository.save(TestData.user(GUARDIAN_ID, "보호자", Role.GUARDIAN));
        Long mine = save(CHANGED_ID, GUARDIAN_ID).getId();
        Long others = save(OTHER_WARD_ID, GUARDIAN_ID).getId();
        // 이 사람이 (보호자 시절) 남에게 등록해 준 약 - 일반 연결 해제와 같은 기준으로 남긴다
        Long registeredByMe = save(OTHER_WARD_ID, CHANGED_ID).getId();

        int stopped = service.stopAllOwnedByWard(CHANGED_ID);
        entityManager.flush();
        entityManager.clear();

        assertThat(stopped).isEqualTo(1);
        assertThat(medicationRepository.findById(mine)).get().matches(Medication::isDeleted);
        // 스케줄러(MedicationReminderPlanner)가 알림 대상을 찾는 쿼리
        assertThat(medicationRepository.findByDeletedAtIsNullAndDoseTimeBetween(LocalTime.of(7, 0), LocalTime.of(9, 0)))
                .extracting(Medication::getId)
                .contains(others, registeredByMe)
                .doesNotContain(mine);
    }

    private Medication save(String wardId, String createdBy) {
        return medicationRepository.save(Medication.builder()
                .wardId(wardId)
                .createdBy(createdBy)
                .name("혈압약")
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(LocalTime.of(8, 0))
                .doseAmount(1)
                .build());
    }
}
