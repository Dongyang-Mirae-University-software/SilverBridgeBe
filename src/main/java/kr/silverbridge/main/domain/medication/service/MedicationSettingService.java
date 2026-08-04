package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import kr.silverbridge.main.domain.medication.repository.MedicationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 피보호자별 복약 알림 ON/OFF 설정.
 *
 * <p><b>기본값 정책</b>: 행이 없는 사용자는 {@link #DEFAULT_ALARM_ENABLED}(ON)을 따른다 — 약을 등록했는데
 * 알림이 꺼져 있는 게 기본이면 기능이 무의미하다. 기존 사용자 백필도 필요 없다({@code SosSettingService}와
 * 동일한 방식).</p>
 *
 * <p><b>인가는 호출자 책임</b>이다 — 이 서비스는 wardId를 그대로 신뢰한다. 보호자 경로의 ACTIVE 연결 검증은
 * {@link GuardianMedicationService}가 수행한 뒤 호출한다.</p>
 *
 * <p>현재는 값을 보관·조회만 한다. 복용 시각 알림 발송(스케줄러)은 2차 과제이며, 그때 이 값이 발송 게이트가 된다.</p>
 */
@Service
@RequiredArgsConstructor
public class MedicationSettingService {

    /** 설정 행이 없을 때 적용되는 기본값. */
    private static final boolean DEFAULT_ALARM_ENABLED = true;

    private final MedicationSettingRepository repository;

    /** 저장된 행이 없으면 기본값을 반환한다. */
    @Transactional(readOnly = true)
    public boolean isAlarmEnabled(String wardId) {
        return repository.findByUserId(wardId)
                .map(MedicationSetting::isAlarmEnabled)
                .orElse(DEFAULT_ALARM_ENABLED);
    }

    /**
     * 여러 피보호자의 설정을 한 번에 조회한다(보호자 목록 화면의 N+1 회피).
     * 저장된 행이 없는 피보호자는 맵에 없으므로 호출자가 기본값으로 채운다.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> findAlarmEnabledByWardIds(Collection<String> wardIds) {
        if (wardIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByUserIdIn(wardIds).stream()
                .collect(Collectors.toMap(MedicationSetting::getUserId, MedicationSetting::isAlarmEnabled));
    }

    /** 설정을 upsert하고 적용된 값을 반환한다. */
    @Transactional
    public boolean updateAlarmEnabled(String wardId, boolean alarmEnabled) {
        repository.findByUserId(wardId)
                .ifPresentOrElse(
                        existing -> existing.updateAlarmEnabled(alarmEnabled),
                        () -> repository.save(MedicationSetting.of(wardId, alarmEnabled)));
        return alarmEnabled;
    }

    /** 설정이 없는 피보호자에게 적용되는 기본값. */
    public static boolean defaultAlarmEnabled() {
        return DEFAULT_ALARM_ENABLED;
    }
}
