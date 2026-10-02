package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import kr.silverbridge.main.domain.medication.repository.MedicationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 피보호자별 복약 알림 설정(알림 ON/OFF, 재알림 ON/OFF).
 *
 * <p><b>기본값 정책</b>: 행이 없는 사용자는 {@link MedicationPreference#DEFAULT}(둘 다 켜짐)를 따른다 —
 * 기존 사용자 백필이 필요 없다({@code SosSettingService}와 동일한 방식).</p>
 *
 * <p><b>인가는 호출자 책임</b>이다 — 이 서비스는 wardId를 그대로 신뢰한다. 보호자 경로의 ACTIVE 연결 검증은
 * {@link GuardianMedicationService}가 수행한 뒤 호출한다.</p>
 */
@Service
@RequiredArgsConstructor
public class MedicationSettingService {

    private final MedicationSettingRepository repository;

    /** 저장된 행이 없으면 기본값을 반환한다. */
    @Transactional(readOnly = true)
    public MedicationPreference getPreference(String wardId) {
        return repository.findByUserId(wardId)
                .map(MedicationSettingService::toPreference)
                .orElse(MedicationPreference.DEFAULT);
    }

    /**
     * 여러 피보호자의 설정을 한 번에 조회한다(보호자 목록 화면·알림 발송의 N+1 회피).
     * <b>요청한 모든 wardId가 결과에 포함</b>되며, 저장된 행이 없는 피보호자는 기본값으로 채워진다.
     */
    @Transactional(readOnly = true)
    public Map<String, MedicationPreference> findPreferences(Collection<String> wardIds) {
        if (wardIds.isEmpty()) {
            return Map.of();
        }
        Map<String, MedicationSetting> stored = repository.findByUserIdIn(wardIds).stream()
                .collect(Collectors.toMap(MedicationSetting::getUserId, Function.identity()));

        Map<String, MedicationPreference> result = new HashMap<>();
        for (String wardId : wardIds) {
            MedicationSetting setting = stored.get(wardId);
            result.put(wardId, setting != null ? toPreference(setting) : MedicationPreference.DEFAULT);
        }
        return result;
    }

    /**
     * 설정을 upsert하고 적용된 값을 반환한다.
     *
     * <p><b>null은 "변경하지 않음"</b>이다 — 프론트가 기존처럼 {@code {alarmEnabled}}만 보내도
     * 재알림 설정이 초기화되지 않는다(하위호환).</p>
     */
    @Transactional
    public MedicationPreference updatePreference(String wardId, Boolean alarmEnabled, Boolean remindAgainEnabled) {
        MedicationSetting setting = repository.findByUserId(wardId)
                .orElseGet(() -> createDefault(wardId));

        if (alarmEnabled != null) {
            setting.updateAlarmEnabled(alarmEnabled);
        }
        if (remindAgainEnabled != null) {
            setting.updateRemindAgainEnabled(remindAgainEnabled);
        }
        return toPreference(setting);
    }

    /**
     * 기본값 행을 만들고 다시 읽는다. 동시 요청이 먼저 만들었으면 그 행을 받아 그대로 갱신한다(MED-G13).
     *
     * <p>{@code save()} 후 UNIQUE 위반을 잡는 방식은 쓰지 않는다 - 예외가 나면 이 트랜잭션은 rollback-only라
     * 재조회·갱신을 이어 갈 수 없다. {@code ON CONFLICT DO NOTHING}은 예외 없이 넘어간다.</p>
     */
    private MedicationSetting createDefault(String wardId) {
        repository.insertIfAbsent(
                wardId,
                MedicationPreference.DEFAULT.alarmEnabled(),
                MedicationPreference.DEFAULT.remindAgainEnabled());
        return repository.findByUserId(wardId)
                .orElseThrow(() -> new IllegalStateException("복약 설정 행 생성 직후 조회 실패: wardId=" + wardId));
    }

    private static MedicationPreference toPreference(MedicationSetting setting) {
        return new MedicationPreference(setting.isAlarmEnabled(), setting.isRemindAgainEnabled());
    }
}
