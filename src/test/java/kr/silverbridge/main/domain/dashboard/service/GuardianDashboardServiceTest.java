package kr.silverbridge.main.domain.dashboard.service;

import kr.silverbridge.main.domain.anomaly.dto.GuardianAnomalyHistorySummary;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardianDashboardServiceTest {

    @Mock ConnectionService connectionService;
    @Mock GuardianAnomalyService anomalyService;
    @Mock GuardianSosService sosService;
    @Mock GuardianMedicationService medicationService;
    @Mock UserRepository userRepository;
    @InjectMocks GuardianDashboardService service;

    private static GuardianAnomalyHistorySummary summary(long pending, long conflicted) {
        return new GuardianAnomalyHistorySummary(pending + conflicted, pending, conflicted, pending + conflicted,
                new GuardianAnomalyHistorySummary.ByType(0, 0, 0));
    }

    /** 복용 시각 00:00 약은 언제 조회해도 "지난 약"이라 시계에 의존하지 않는다. */
    private static WardMedicationSummary medSummary(String wardId, MedicationItem... items) {
        return new WardMedicationSummary(wardId, wardId + "-name", null, true, true, true, null,
                LocalDate.now(), 0, items.length, List.of(items));
    }

    private static MedicationItem item(long id, LocalTime time, boolean taken) {
        return new MedicationItem(id, "약" + id, MedicationTimeSlot.MORNING, time, 1, null, taken, null);
    }

    @Test
    @DisplayName("연결 0명 → 하위 서비스를 호출하지 않고 빈 응답")
    void 연결없음_빈응답() {
        when(connectionService.getActiveWardIds("G1")).thenReturn(List.of());

        GuardianDashboardResponse res = service.getDashboard("G1", null);

        assertThat(res.wards()).isEmpty();
        assertThat(res.anomalyDetection()).isNull();
        assertThat(res.sos()).isNull();
        assertThat(res.medication()).isNull();
        assertThat(res.unavailable()).isEmpty();
        verifyNoInteractions(anomalyService, sosService, medicationService);
    }

    @Test
    @DisplayName("연결 없는 wardId → 403, 하위 서비스 호출 없음")
    void 연결없는_wardId_403() {
        when(connectionService.isActiveConnection("G1", "W9")).thenReturn(false);

        assertThatThrownBy(() -> service.getDashboard("G1", "W9"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DASHBOARD_NOT_AUTHORIZED));
        verifyNoInteractions(anomalyService, sosService, medicationService);
        verify(connectionService, never()).getActiveWardIds(any());
    }

    @Test
    @DisplayName("wardId 생략 → 전원 합산, 복약은 인가된 피보호자만 센다")
    void 생략_합산() {
        when(connectionService.getActiveWardIds("G1")).thenReturn(List.of("W1", "W2"));
        when(anomalyService.getHistorySummary("G1", "W1")).thenReturn(summary(1, 1));
        when(anomalyService.getHistorySummary("G1", "W2")).thenReturn(summary(0, 0));
        when(anomalyService.getLatestNeedsReview("G1", null)).thenReturn(null);
        when(sosService.getRecent(anyString(), any(), any())).thenReturn(new GuardianSosService.RecentSos(2, null));
        when(medicationService.getWardMedications("G1")).thenReturn(List.of(
                medSummary("W1", item(1, LocalTime.MIDNIGHT, false), item(2, LocalTime.MIDNIGHT, true)),
                medSummary("W2", item(3, LocalTime.MIDNIGHT, false)),
                medSummary("W3", item(4, LocalTime.MIDNIGHT, false)))); // 인가 밖 → 제외

        GuardianDashboardResponse res = service.getDashboard("G1", null);

        assertThat(res.anomalyDetection().needsReviewCount()).isEqualTo(2);
        assertThat(res.medication().uncheckedCount()).isEqualTo(2);
        assertThat(res.sos().thisMonthCount()).isEqualTo(2);
        assertThat(res.pendingActions().total()).isEqualTo(4);
        assertThat(res.wards()).extracting(GuardianDashboardResponse.WardChip::pendingCount)
                .containsExactly(3L, 1L);
        assertThat(res.unavailable()).isEmpty();
    }

    @Test
    @DisplayName("wardId 지정 → 그 피보호자 기준으로만 하위 서비스 호출")
    void 지정_범위() {
        when(connectionService.isActiveConnection("G1", "W1")).thenReturn(true);
        when(anomalyService.getHistorySummary("G1", "W1")).thenReturn(summary(1, 0));
        when(anomalyService.getLatestNeedsReview("G1", "W1")).thenReturn(null);
        when(sosService.getRecent(anyString(), any(), any())).thenReturn(new GuardianSosService.RecentSos(0, null));
        when(medicationService.getWardMedications("G1")).thenReturn(List.of(
                medSummary("W1"), medSummary("W2", item(3, LocalTime.MIDNIGHT, false))));

        GuardianDashboardResponse res = service.getDashboard("G1", "W1");

        assertThat(res.wards()).hasSize(1);
        assertThat(res.medication().uncheckedCount()).isZero();
        assertThat(res.medication().mostUrgent()).isNull();
        verify(sosService).getRecent(anyString(), org.mockito.ArgumentMatchers.eq("W1"), any());
    }

    @Test
    @DisplayName("복약 칸 실패 → 0이 아니라 null, total도 null, 나머지 칸은 내려간다")
    void 칸_실패_격리() {
        when(connectionService.getActiveWardIds("G1")).thenReturn(List.of("W1"));
        when(anomalyService.getHistorySummary("G1", "W1")).thenReturn(summary(1, 0));
        when(anomalyService.getLatestNeedsReview("G1", null)).thenReturn(null);
        when(sosService.getRecent(anyString(), any(), any())).thenReturn(new GuardianSosService.RecentSos(0, null));
        when(medicationService.getWardMedications("G1")).thenThrow(new IllegalStateException("db down"));

        GuardianDashboardResponse res = service.getDashboard("G1", null);

        assertThat(res.medication()).isNull();
        assertThat(res.unavailable()).containsExactly("medication");
        assertThat(res.pendingActions().medication()).isNull();
        assertThat(res.pendingActions().total()).isNull();
        assertThat(res.pendingActions().anomaly()).isEqualTo(1);
        assertThat(res.anomalyDetection().needsReviewCount()).isEqualTo(1);
        assertThat(res.wards().get(0).pendingCount()).isNull();
    }

    @Test
    @DisplayName("이상감지·SOS 칸 실패 → 각각 null + unavailable")
    void 이상감지_SOS_실패() {
        when(connectionService.getActiveWardIds("G1")).thenReturn(List.of("W1"));
        when(anomalyService.getHistorySummary("G1", "W1")).thenThrow(new IllegalStateException("x"));
        when(sosService.getRecent(anyString(), any(), any())).thenThrow(new IllegalStateException("x"));
        when(medicationService.getWardMedications("G1")).thenReturn(List.of(medSummary("W1")));

        GuardianDashboardResponse res = service.getDashboard("G1", null);

        assertThat(res.anomalyDetection()).isNull();
        assertThat(res.sos()).isNull();
        assertThat(res.unavailable()).containsExactlyInAnyOrder("anomalyDetection", "sos");
        assertThat(res.pendingActions().total()).isNull();
    }

    @Test
    @DisplayName("미체크 판정: 복용 시각 지난 + 미체크만, 정각 포함, 가장 이른 시각 순")
    void 미체크_판정() {
        WardMedicationSummary s = medSummary("W1",
                item(1, LocalTime.of(13, 0), false),
                item(2, LocalTime.of(8, 0), false),
                item(3, LocalTime.of(9, 0), true),
                item(4, LocalTime.of(14, 0), false));

        var result = GuardianDashboardService.uncheckedOf(s, LocalTime.of(13, 0));

        assertThat(result).extracting(GuardianDashboardResponse.UncheckedMedication::medicationId)
                .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("이번 달 시작은 KST 1일 00:00 - 말일 23:59와 1일 00:00이 서로 다른 달로 갈린다")
    void 월경계_KST() {
        OffsetDateTime start = GuardianDashboardService.monthStart(LocalDate.of(2026, 10, 31));
        assertThat(start).isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(GuardianDashboardService.monthStart(LocalDate.of(2026, 11, 1)))
                .isEqualTo(OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("응답에 제외한 칸(정서·활동·병원 예약) 필드가 없다")
    void 제외칸_필드없음() {
        assertThat(GuardianDashboardResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("pendingActions", "wards", "anomalyDetection", "sos", "medication", "unavailable");
    }
}
