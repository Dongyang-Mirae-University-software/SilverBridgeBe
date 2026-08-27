package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 보호자 본인의 복약 알림 수신 설정.
 *
 * <p>피보호자 단위인 {@link MedicationSettingService}(복용 알림·재알림)와 축이 다르다 -
 * 이쪽은 "내가 무엇을 언제 받을지"라 보호자 자신이 정하며 연결 검증이 필요 없다.</p>
 *
 * <p><b>기본값 ON</b>: 행이 없으면 받는다. 기본 OFF면 아무도 켜지 않아 기능이 죽고, 끌 수단이 없으면
 * 알림 피로로 앱 알림을 통째로 꺼 SOS·이상감지까지 함께 죽는다 - 켜두되 이것만 끌 수 있게 한다.</p>
 *
 * <p><b>기본 시각</b>은 저장하지 않고 {@link MedicationProperties}에서 읽는다. 미설정 행에 21:00을
 * 박아 두면 서버 기본값을 바꿔도 기존 보호자가 따라오지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
public class GuardianMedicationSettingService {

    private static final boolean DEFAULT_MISSED_ALERT_ENABLED = true;

    private final GuardianMedicationSettingRepository repository;
    private final MedicationProperties properties;

    /** 설정이 없는 보호자에게 적용되는 실효값. */
    public GuardianMissedAlertSetting defaultSetting() {
        return new GuardianMissedAlertSetting(DEFAULT_MISSED_ALERT_ENABLED, defaultAlertTime());
    }

    /** 저장된 행이 없으면 기본값을 반환한다. */
    @Transactional(readOnly = true)
    public GuardianMissedAlertSetting getSetting(String guardianId) {
        return repository.findByGuardianId(guardianId)
                .map(this::toEffective)
                .orElseGet(this::defaultSetting);
    }

    /**
     * 여러 보호자의 설정을 한 번에 조회한다(발송 시 N+1 회피).
     * 저장된 행이 없는 보호자는 맵에 없으므로 호출자가 {@link #defaultSetting()}으로 채운다.
     */
    @Transactional(readOnly = true)
    public Map<String, GuardianMissedAlertSetting> findSettings(Collection<String> guardianIds) {
        if (guardianIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByGuardianIdIn(guardianIds).stream()
                .collect(Collectors.toMap(
                        GuardianMedicationSetting::getGuardianId,
                        this::toEffective));
    }

    /**
     * 설정을 upsert하고 적용된 실효값을 반환한다. {@code null} 필드는 변경하지 않는다.
     *
     * <p>시각을 기본값으로 되돌리려면 기본 시각을 직접 지정한다 - {@code null}은 이 API 공통 규약상
     * "변경하지 않음"이라 초기화 신호로 쓸 수 없다.</p>
     */
    @Transactional
    public GuardianMissedAlertSetting update(String guardianId, Boolean missedAlertEnabled, LocalTime missedAlertTime) {
        GuardianMedicationSetting setting = repository.findByGuardianId(guardianId)
                .orElseGet(() -> repository.save(
                        GuardianMedicationSetting.of(guardianId, DEFAULT_MISSED_ALERT_ENABLED)));

        if (missedAlertEnabled != null) {
            setting.updateMissedAlertEnabled(missedAlertEnabled);
        }
        if (missedAlertTime != null) {
            setting.updateMissedAlertTime(missedAlertTime);
        }
        return toEffective(setting);
    }

    /** 저장된 행을 실효값으로 바꾼다 - 시각이 비어 있으면 기본 시각을 채운다. */
    private GuardianMissedAlertSetting toEffective(GuardianMedicationSetting setting) {
        LocalTime alertTime = setting.getMissedAlertTime() != null
                ? setting.getMissedAlertTime()
                : defaultAlertTime();
        return new GuardianMissedAlertSetting(setting.isMissedAlertEnabled(), alertTime);
    }

    private LocalTime defaultAlertTime() {
        return properties.getMissedAlert().getAlertTime();
    }
}
