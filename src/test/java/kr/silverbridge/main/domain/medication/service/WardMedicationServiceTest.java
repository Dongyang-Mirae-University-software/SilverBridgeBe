package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.TodayMedicationResponse;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationIntake;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.event.MedicationIntakeChangedEvent;
import kr.silverbridge.main.domain.medication.repository.MedicationIntakeRepository;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WardMedicationService 단위 테스트.
 *
 * <p>검증 축은 셋이다 — ① <b>본인 약만</b> 체크할 수 있는가(IDOR 차단) ② <b>멱등</b>한가(중복 체크·해제가
 * 오류가 아니고 알림도 반복되지 않는가) ③ 오늘 일정·카운트가 정확한가.</p>
 */
@ExtendWith(MockitoExtension.class)
class WardMedicationServiceTest {

    @Mock private MedicationRepository medicationRepository;
    @Mock private MedicationIntakeRepository intakeRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private WardMedicationService wardMedicationService;

    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD0002";

    // ─── 오늘의 일정 ────────────────────────────────────────────────

    @Test
    @DisplayName("오늘의 일정 — 복용 체크된 약만 taken=true, 카운트는 '체크 수/전체 수'")
    void getToday_카운트() {
        LocalDate today = MedicationClock.today();
        when(medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(WARD_ID))
                .thenReturn(List.of(
                        medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0)),
                        medication(2L, WARD_ID, "당뇨약", LocalTime.of(13, 0)),
                        medication(3L, WARD_ID, "수면 보조제", LocalTime.of(22, 0))));
        when(intakeRepository.findByMedicationIdInAndDoseDate(any(), eq(today)))
                .thenReturn(List.of(MedicationIntake.of(2L, today, OffsetDateTime.now())));

        TodayMedicationResponse response = wardMedicationService.getToday(WARD_ID);

        assertThat(response.doseDate()).isEqualTo(today);
        assertThat(response.takenCount()).isEqualTo(1);
        assertThat(response.totalCount()).isEqualTo(3);
        assertThat(response.medications()).extracting(MedicationItem::taken)
                .containsExactly(false, true, false);
    }

    @Test
    @DisplayName("등록된 약이 없으면 0/0 — 복용 체크를 조회하지 않는다")
    void getToday_약없음() {
        when(medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(WARD_ID))
                .thenReturn(List.of());

        TodayMedicationResponse response = wardMedicationService.getToday(WARD_ID);

        assertThat(response.medications()).isEmpty();
        assertThat(response.totalCount()).isZero();
        verify(intakeRepository, never()).findByMedicationIdInAndDoseDate(any(), any());
    }

    // ─── 복용 체크 ──────────────────────────────────────────────────

    @Test
    @DisplayName("복용 체크 — 오늘 날짜로 기록하고 실시간 반영 이벤트를 발행한다")
    void markTaken_기록_이벤트발행() {
        LocalDate today = MedicationClock.today();
        Medication medication = medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0));
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(medication));
        when(intakeRepository.findByMedicationIdAndDoseDate(1L, today)).thenReturn(Optional.empty());
        when(intakeRepository.save(any(MedicationIntake.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MedicationItem item = wardMedicationService.markTaken(WARD_ID, 1L);

        assertThat(item.taken()).isTrue();
        assertThat(item.takenAt()).isNotNull();

        ArgumentCaptor<MedicationIntake> intakeCaptor = ArgumentCaptor.forClass(MedicationIntake.class);
        verify(intakeRepository).save(intakeCaptor.capture());
        assertThat(intakeCaptor.getValue().getDoseDate()).isEqualTo(today);

        ArgumentCaptor<MedicationIntakeChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(MedicationIntakeChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().taken()).isTrue();
        assertThat(eventCaptor.getValue().wardId()).isEqualTo(WARD_ID);
        assertThat(eventCaptor.getValue().medicationName()).isEqualTo("혈압약");
    }

    @Test
    @DisplayName("이미 체크된 약을 다시 체크 → 오류 아님(멱등), 중복 저장·중복 알림 없음")
    void markTaken_중복_멱등() {
        LocalDate today = MedicationClock.today();
        OffsetDateTime takenAt = OffsetDateTime.now().minusHours(1);
        when(medicationRepository.findById(1L))
                .thenReturn(Optional.of(medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0))));
        when(intakeRepository.findByMedicationIdAndDoseDate(1L, today))
                .thenReturn(Optional.of(MedicationIntake.of(1L, today, takenAt)));

        MedicationItem item = wardMedicationService.markTaken(WARD_ID, 1L);

        assertThat(item.taken()).isTrue();
        // 최초 체크 시각이 유지된다 — 다시 눌렀다고 시각이 갱신되면 "언제 드셨는지"가 어긋난다.
        assertThat(item.takenAt()).isEqualTo(takenAt);
        verify(intakeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(MedicationIntakeChangedEvent.class));
    }

    @Test
    @DisplayName("[IDOR] 타인의 약을 체크하려 하면 403 — 기록도 알림도 없다")
    void markTaken_타인약_차단() {
        when(medicationRepository.findById(1L))
                .thenReturn(Optional.of(medication(1L, OTHER_WARD_ID, "혈압약", LocalTime.of(8, 0))));

        assertThatThrownBy(() -> wardMedicationService.markTaken(WARD_ID, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_OWNED);

        // 보호자용 문구("연결된 피보호자의…")를 재사용하면 피보호자에게 뜻이 통하지 않는다.
        // 403을 쓰는 이유가 "무슨 일이 일어났는지 그대로 안내"(2026-07-14)이므로 수신자 기준 문구를 고정한다.
        assertThat(ErrorCode.MEDICATION_NOT_OWNED.getMessage())
                .isEqualTo("본인의 약만 체크할 수 있습니다.")
                .doesNotContain("연결된 피보호자");

        verify(intakeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(MedicationIntakeChangedEvent.class));
    }

    @Test
    @DisplayName("삭제된 약은 체크할 수 없다 → 404")
    void markTaken_삭제된약_404() {
        Medication deleted = medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0));
        deleted.delete(OffsetDateTime.now());
        when(medicationRepository.findById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> wardMedicationService.markTaken(WARD_ID, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_FOUND);
    }

    // ─── 체크 해제 ──────────────────────────────────────────────────

    @Test
    @DisplayName("체크 해제 — 기록을 지우고 taken=false 이벤트를 발행한다")
    void unmarkTaken_해제_이벤트발행() {
        LocalDate today = MedicationClock.today();
        MedicationIntake intake = MedicationIntake.of(1L, today, OffsetDateTime.now());
        when(medicationRepository.findById(1L))
                .thenReturn(Optional.of(medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0))));
        when(intakeRepository.findByMedicationIdAndDoseDate(1L, today)).thenReturn(Optional.of(intake));

        MedicationItem item = wardMedicationService.unmarkTaken(WARD_ID, 1L);

        assertThat(item.taken()).isFalse();
        assertThat(item.takenAt()).isNull();
        verify(intakeRepository).delete(intake);

        ArgumentCaptor<MedicationIntakeChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(MedicationIntakeChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().taken()).isFalse();
        assertThat(eventCaptor.getValue().takenAt()).isNull();
    }

    @Test
    @DisplayName("체크되지 않은 약을 해제 → 오류 아님(멱등), 삭제·알림 없음")
    void unmarkTaken_미체크_멱등() {
        LocalDate today = MedicationClock.today();
        when(medicationRepository.findById(1L))
                .thenReturn(Optional.of(medication(1L, WARD_ID, "혈압약", LocalTime.of(8, 0))));
        when(intakeRepository.findByMedicationIdAndDoseDate(1L, today)).thenReturn(Optional.empty());

        assertThat(wardMedicationService.unmarkTaken(WARD_ID, 1L).taken()).isFalse();

        verify(intakeRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any(MedicationIntakeChangedEvent.class));
    }

    @Test
    @DisplayName("[IDOR] 타인의 약을 해제하려 하면 403")
    void unmarkTaken_타인약_차단() {
        when(medicationRepository.findById(1L))
                .thenReturn(Optional.of(medication(1L, OTHER_WARD_ID, "혈압약", LocalTime.of(8, 0))));

        assertThatThrownBy(() -> wardMedicationService.unmarkTaken(WARD_ID, 1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_OWNED);

        verify(intakeRepository, never()).delete(any());
    }

    private static Medication medication(Long id, String wardId, String name, LocalTime doseTime) {
        Medication medication = Medication.builder()
                .wardId(wardId)
                .createdBy("GD0001")
                .name(name)
                .timeSlot(MedicationTimeSlot.MORNING)
                .doseTime(doseTime)
                .doseAmount(1)
                .build();
        ReflectionTestUtils.setField(medication, "id", id);
        return medication;
    }
}
