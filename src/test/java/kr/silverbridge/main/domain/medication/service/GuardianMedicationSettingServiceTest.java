package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GuardianMedicationSettingService 단위 테스트.
 *
 * <p>축이 (보호자) → (보호자, 피보호자)로 바뀌면서(2026-08-27, V41) <b>남의 피보호자 설정을 건드릴 수
 * 있는 경로</b>가 생겼다. 그래서 인가 검증이 이 클래스의 핵심 축이다.</p>
 *
 * <p>나머지 축 - 저장 행이 없을 때의 기본값, null=미변경 규약, 시각의 분 단위 절삭.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardianMedicationSettingServiceTest {

    @Mock private GuardianMedicationSettingRepository repository;
    @Mock private ConnectionService connectionService;

    private MedicationProperties properties;
    private GuardianMedicationSettingService service;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final String OTHER_WARD_ID = "WD9999";
    private static final LocalTime DEFAULT_TIME = LocalTime.of(21, 0);

    @BeforeEach
    void setUp() {
        properties = new MedicationProperties();
        properties.getMissedAlert().setAlertTime(DEFAULT_TIME);
        service = new GuardianMedicationSettingService(repository, connectionService, properties);

        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);
        when(connectionService.isActiveConnection(GUARDIAN_ID, OTHER_WARD_ID)).thenReturn(false);
    }

    // ─── 인가 ────────────────────────────────────────────────

    @Test
    @DisplayName("연결되지 않은 피보호자의 설정은 조회할 수 없다 - 403")
    void 조회_인가위반() {
        assertThatThrownBy(() -> service.getSetting(GUARDIAN_ID, OTHER_WARD_ID))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        verify(repository, never()).findByGuardianIdAndWardId(any(), any());
    }

    @Test
    @DisplayName("연결되지 않은 피보호자의 설정은 변경할 수 없다 - 403 (행도 만들지 않는다)")
    void 변경_인가위반() {
        assertThatThrownBy(() -> service.update(GUARDIAN_ID, OTHER_WARD_ID, true, LocalTime.of(19, 0)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEDICATION_NOT_AUTHORIZED);

        verify(repository, never()).save(any());
    }

    // ─── 기본값 ──────────────────────────────────────────────

    @Test
    @DisplayName("저장된 행이 없으면 기본값(ON · 전역 기본 시각)")
    void 미설정_기본값() {
        when(repository.findByGuardianIdAndWardId(GUARDIAN_ID, WARD_ID)).thenReturn(Optional.empty());

        assertThat(service.getSetting(GUARDIAN_ID, WARD_ID))
                .isEqualTo(new GuardianMissedAlertSetting(true, DEFAULT_TIME));
    }

    @Test
    @DisplayName("시각만 비어 있으면 전역 기본 시각으로 채워 돌려준다 - 미설정 여부를 노출하지 않는다")
    void 시각미설정_기본시각() {
        when(repository.findByGuardianIdAndWardId(GUARDIAN_ID, WARD_ID))
                .thenReturn(Optional.of(GuardianMedicationSetting.of(GUARDIAN_ID, WARD_ID, false)));

        assertThat(service.getSetting(GUARDIAN_ID, WARD_ID))
                .isEqualTo(new GuardianMissedAlertSetting(false, DEFAULT_TIME));
    }

    // ─── 변경 규약 ───────────────────────────────────────────

    @Test
    @DisplayName("null 필드는 변경하지 않는다 - 시각만 바꿔도 수신 여부가 초기화되지 않는다")
    void null은_미변경() {
        GuardianMedicationSetting stored = GuardianMedicationSetting.of(GUARDIAN_ID, WARD_ID, false);
        when(repository.findByGuardianIdAndWardId(GUARDIAN_ID, WARD_ID)).thenReturn(Optional.of(stored));

        GuardianMissedAlertSetting result = service.update(GUARDIAN_ID, WARD_ID, null, LocalTime.of(19, 30));

        assertThat(result.enabled()).isFalse();                          // 유지
        assertThat(result.alertTime()).isEqualTo(LocalTime.of(19, 30));  // 변경
    }

    @Test
    @DisplayName("시각은 분 단위로 잘라 저장한다 - 스케줄러가 분 단위라 초는 무의미하다")
    void 시각_분단위_절삭() {
        GuardianMedicationSetting stored = GuardianMedicationSetting.of(GUARDIAN_ID, WARD_ID, true);
        when(repository.findByGuardianIdAndWardId(GUARDIAN_ID, WARD_ID)).thenReturn(Optional.of(stored));

        GuardianMissedAlertSetting result =
                service.update(GUARDIAN_ID, WARD_ID, null, LocalTime.of(19, 30, 45, 123));

        assertThat(result.alertTime()).isEqualTo(LocalTime.of(19, 30));
    }

    @Test
    @DisplayName("설정 행이 없으면 만들어서 변경한다")
    void 미설정_upsert() {
        when(repository.findByGuardianIdAndWardId(GUARDIAN_ID, WARD_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GuardianMissedAlertSetting result = service.update(GUARDIAN_ID, WARD_ID, false, null);

        assertThat(result.enabled()).isFalse();
        assertThat(result.alertTime()).isEqualTo(DEFAULT_TIME);
        verify(repository).save(any());
    }

    // ─── 벌크 조회 ───────────────────────────────────────────

    @Test
    @DisplayName("발송용 조회는 피보호자를 키로 좁힌다 - 다른 피보호자 설정이 섞이면 안 된다")
    void 발송용_조회는_피보호자별() {
        GuardianMedicationSetting stored = GuardianMedicationSetting.of(GUARDIAN_ID, WARD_ID, true);
        stored.updateMissedAlertTime(LocalTime.of(22, 30));
        when(repository.findByWardIdAndGuardianIdIn(WARD_ID, List.of(GUARDIAN_ID)))
                .thenReturn(List.of(stored));

        Map<String, GuardianMissedAlertSetting> settings = service.findSettings(WARD_ID, List.of(GUARDIAN_ID));

        assertThat(settings).containsEntry(GUARDIAN_ID, new GuardianMissedAlertSetting(true, LocalTime.of(22, 30)));
        verify(repository).findByWardIdAndGuardianIdIn(WARD_ID, List.of(GUARDIAN_ID));
    }

    @Test
    @DisplayName("카드 목록용 조회는 내 설정만 피보호자별로 모은다")
    void 카드목록용_조회() {
        GuardianMedicationSetting a = GuardianMedicationSetting.of(GUARDIAN_ID, WARD_ID, true);
        a.updateMissedAlertTime(LocalTime.of(22, 30));
        GuardianMedicationSetting b = GuardianMedicationSetting.of(GUARDIAN_ID, "WD0002", false);
        when(repository.findByGuardianIdAndWardIdIn(eq(GUARDIAN_ID), any())).thenReturn(List.of(a, b));

        Map<String, GuardianMissedAlertSetting> settings =
                service.findSettingsOfGuardian(GUARDIAN_ID, List.of(WARD_ID, "WD0002"));

        assertThat(settings)
                .containsEntry(WARD_ID, new GuardianMissedAlertSetting(true, LocalTime.of(22, 30)))
                .containsEntry("WD0002", new GuardianMissedAlertSetting(false, DEFAULT_TIME));
    }

    @Test
    @DisplayName("대상이 없으면 조회하지 않는다")
    void 빈목록_조회안함() {
        assertThat(service.findSettings(WARD_ID, List.of())).isEmpty();
        assertThat(service.findSettingsOfGuardian(GUARDIAN_ID, List.of())).isEmpty();

        verify(repository, never()).findByWardIdAndGuardianIdIn(any(), any());
        verify(repository, never()).findByGuardianIdAndWardIdIn(any(), any());
    }
}
