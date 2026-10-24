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
 * 피보호자별 복약 알림 설정 조회(재알림 ON/OFF). 복용 시각 알림 자체는 늘 보낸다 - 켜고 끄는 설정 API는
 * 2026-10-24 사용자 결정으로 없앴다.
 *
 * <p><b>기본값 정책</b>: 행이 없는 사용자는 {@link MedicationPreference#DEFAULT}(재알림 켜짐)를 따른다 —
 * 기존 사용자 백필이 필요 없다({@code SosSettingService}와 동일한 방식).</p>
 *
 * <p><b>인가는 호출자 책임</b>이다 — 이 서비스는 wardId를 그대로 신뢰한다(보호자 카드·발송 스케줄러가 이미 좁힌 뒤 부른다).</p>
 */
@Service
@RequiredArgsConstructor
public class MedicationSettingService {

    private final MedicationSettingRepository repository;

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

    private static MedicationPreference toPreference(MedicationSetting setting) {
        return new MedicationPreference(setting.isRemindAgainEnabled());
    }
}
