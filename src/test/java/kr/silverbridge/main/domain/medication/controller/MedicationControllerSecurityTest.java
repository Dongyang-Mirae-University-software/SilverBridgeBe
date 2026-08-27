package kr.silverbridge.main.domain.medication.controller;

import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.GuardianMedicationAlertSettingRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationSettingUpdateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
import kr.silverbridge.main.domain.medication.dto.TodayMedicationResponse;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import kr.silverbridge.main.domain.medication.service.GuardianMissedAlertSetting;
import kr.silverbridge.main.domain.medication.service.WardMedicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 복약 API 권한 테스트 — <b>역할 분리가 요구사항 그 자체</b>라 구조로 지켜지는지 확인한다.
 *
 * <p>① 약 등록·삭제·알림 설정은 <b>보호자만</b> (피보호자는 자기 약을 추가할 수 없다)
 * ② 복용 체크는 <b>피보호자만</b> (보호자가 대신 체크하면 "피보호자가 체크해야 보인다"는 요구가 깨진다).
 * 연결 여부에 따른 IDOR 차단은 서비스 테스트가 담당한다.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        MedicationControllerSecurityTest.MethodSecurityTestConfig.class,
        GuardianMedicationController.class,
        WardMedicationController.class
})
class MedicationControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @MockitoBean
    private GuardianMedicationService guardianMedicationService;
    @MockitoBean
    private WardMedicationService wardMedicationService;
    @MockitoBean
    private GuardianMedicationSettingService guardianMedicationSettingService;

    @Autowired
    private GuardianMedicationController guardianController;
    @Autowired
    private WardMedicationController wardController;

    private static final MedicationCreateRequest CREATE_REQUEST =
            new MedicationCreateRequest("혈압약", MedicationTimeSlot.MORNING, null, 1, null);

    // ─── 보호자 전용 경로 ────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 피보호자 복약 현황 조회·약 등록 허용")
    void guardian_보호자API_허용() {
        when(guardianMedicationService.getWardMedications(any())).thenReturn(List.of());
        when(guardianMedicationService.create(any(), any(), any())).thenReturn(null);
        when(guardianMedicationService.update(any(), any(), any())).thenReturn(null);
        GuardianMissedAlertSetting setting = new GuardianMissedAlertSetting(true, LocalTime.of(21, 0));
        when(guardianMedicationSettingService.getSetting(any())).thenReturn(setting);
        when(guardianMedicationSettingService.update(any(), any(), any())).thenReturn(setting);

        assertThatNoException().isThrownBy(() -> guardianController.getWardMedications("GD0001"));
        assertThatNoException().isThrownBy(() -> guardianController.create("GD0001", "WD0001", CREATE_REQUEST));
        assertThatNoException().isThrownBy(() -> guardianController.delete("GD0001", 1L));
        assertThatNoException().isThrownBy(() -> guardianController.update(
                "GD0001", 1L, new MedicationUpdateRequest("혈압약", null, null, null, null)));
        assertThatNoException().isThrownBy(() -> guardianController.getAlertSetting("GD0001"));
        assertThatNoException().isThrownBy(() -> guardianController.updateAlertSetting(
                "GD0001", new GuardianMedicationAlertSettingRequest(false, null)));
    }

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 약 등록·삭제·알림 설정 거부 (약 추가는 보호자만)")
    void ward_보호자API_거부() {
        assertThatThrownBy(() -> guardianController.create("WD0001", "WD0001", CREATE_REQUEST))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardianController.delete("WD0001", 1L))
                .isInstanceOf(AccessDeniedException.class);
        // 약 수정도 보호자 전용 — 피보호자는 자기 약도 고칠 수 없다(등록·수정·삭제는 보호자 몫)
        assertThatThrownBy(() -> guardianController.update(
                "WD0001", 1L, new MedicationUpdateRequest("혈압약", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardianController.updateSetting("WD0001", "WD0001",
                new MedicationSettingUpdateRequest(false, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardianController.getWardMedications("WD0001"))
                .isInstanceOf(AccessDeniedException.class);
        // 미복용 요약 수신 설정도 보호자 전용 — 피보호자가 건드릴 수 없다
        assertThatThrownBy(() -> guardianController.getAlertSetting("WD0001"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guardianController.updateAlertSetting(
                "WD0001", new GuardianMedicationAlertSettingRequest(true, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN → 보호자 복약 API 거부")
    void admin_보호자API_거부() {
        assertThatThrownBy(() -> guardianController.getWardMedications("AD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ─── 피보호자 전용 경로 ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "WARD")
    @DisplayName("WARD → 오늘 일정 조회·복용 체크·해제 허용")
    void ward_피보호자API_허용() {
        when(wardMedicationService.getToday(anyString()))
                .thenReturn(TodayMedicationResponse.of(LocalDate.now(), List.of()));
        when(wardMedicationService.markTaken(anyString(), any())).thenReturn(null);
        when(wardMedicationService.unmarkTaken(anyString(), any())).thenReturn(null);

        assertThatNoException().isThrownBy(() -> wardController.getToday("WD0001"));
        assertThatNoException().isThrownBy(() -> wardController.markTaken("WD0001", 1L));
        assertThatNoException().isThrownBy(() -> wardController.unmarkTaken("WD0001", 1L));
    }

    @Test
    @WithMockUser(roles = "GUARDIAN")
    @DisplayName("GUARDIAN → 복용 체크 거부 — 보호자는 대신 체크할 수 없다(요구사항 R3)")
    void guardian_복용체크_거부() {
        assertThatThrownBy(() -> wardController.markTaken("GD0001", 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> wardController.unmarkTaken("GD0001", 1L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> wardController.getToday("GD0001"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
