package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 보호자 본인의 복약 알림 수신 설정.
 *
 * <p>피보호자 단위인 {@link MedicationSettingService}(복용 알림·재알림)와 축이 다르다 —
 * 이쪽은 "내가 무엇을 받을지"라 보호자 자신이 정하며 연결 검증이 필요 없다.</p>
 *
 * <p><b>기본값 ON</b>: 행이 없으면 받는다. 기본 OFF면 아무도 켜지 않아 기능이 죽고, 끌 수단이 없으면
 * 알림 피로로 앱 알림을 통째로 꺼 SOS·이상감지까지 함께 죽는다 — 켜두되 이것만 끌 수 있게 한다.</p>
 */
@Service
@RequiredArgsConstructor
public class GuardianMedicationSettingService {

    private static final boolean DEFAULT_MISSED_ALERT_ENABLED = true;

    private final GuardianMedicationSettingRepository repository;

    /** 설정이 없는 보호자에게 적용되는 기본값. */
    public static boolean defaultMissedAlertEnabled() {
        return DEFAULT_MISSED_ALERT_ENABLED;
    }

    /** 저장된 행이 없으면 기본값을 반환한다. */
    @Transactional(readOnly = true)
    public boolean isMissedAlertEnabled(String guardianId) {
        return repository.findByGuardianId(guardianId)
                .map(GuardianMedicationSetting::isMissedAlertEnabled)
                .orElse(DEFAULT_MISSED_ALERT_ENABLED);
    }

    /**
     * 여러 보호자의 설정을 한 번에 조회한다(발송 시 N+1 회피).
     * 저장된 행이 없는 보호자는 맵에 없으므로 호출자가 기본값으로 채운다.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> findMissedAlertEnabled(Collection<String> guardianIds) {
        if (guardianIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByGuardianIdIn(guardianIds).stream()
                .collect(Collectors.toMap(
                        GuardianMedicationSetting::getGuardianId,
                        GuardianMedicationSetting::isMissedAlertEnabled));
    }

    /** 설정을 upsert하고 적용된 값을 반환한다. {@code null}이면 변경하지 않는다. */
    @Transactional
    public boolean updateMissedAlertEnabled(String guardianId, Boolean missedAlertEnabled) {
        GuardianMedicationSetting setting = repository.findByGuardianId(guardianId)
                .orElseGet(() -> repository.save(
                        GuardianMedicationSetting.of(guardianId, DEFAULT_MISSED_ALERT_ENABLED)));

        if (missedAlertEnabled != null) {
            setting.updateMissedAlertEnabled(missedAlertEnabled);
        }
        return setting.isMissedAlertEnabled();
    }
}
