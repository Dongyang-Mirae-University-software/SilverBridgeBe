package kr.silverbridge.main.domain.notification.service;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingRequest;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingResponse;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingResponse.TypeSetting;
import kr.silverbridge.main.domain.notification.entity.GuardianNotificationPreference;
import kr.silverbridge.main.domain.notification.repository.GuardianNotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 알림 종류별 수신 설정 조회·변경.
 *
 * <p><b>이 설정은 "보내지 않는 쪽으로만" 작용한다.</b> SOS·이상감지 발생 알림은 필수라 항상 켜짐으로 내리고
 * 변경 경로가 없다. 게이트는 해당 알림의 플래너가 건다 - {@code NotificationDispatcher}를 거치지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
public class GuardianNotificationPreferenceService {

    private static final boolean DEFAULT_MEDICATION_ENABLED = true;

    private final GuardianNotificationPreferenceRepository repository;
    private final GuardianAnomalySettingService anomalySettingService;

    @Transactional(readOnly = true)
    public GuardianNotificationTypeSettingResponse getSettings(String guardianId) {
        return toResponse(guardianId);
    }

    /** 전달된 종류만 갱신한다. 행이 없으면 {@code ON CONFLICT DO NOTHING} 후 재조회한다. */
    @Transactional
    public GuardianNotificationTypeSettingResponse updateSettings(String guardianId,
                                                                  GuardianNotificationTypeSettingRequest request) {
        Boolean medication = request.medication();
        if (medication != null && repository.insertIfAbsent(guardianId, medication) == 0) {
            repository.findByGuardianId(guardianId)
                    .orElseThrow(() -> new IllegalStateException("알림 종류 설정 동시 저장 후 행을 찾지 못함"))
                    .changeMedicationEnabled(medication);
        }
        if (request.anomalyReviewReminder() != null) {
            anomalySettingService.updateSetting(guardianId, request.anomalyReviewReminder());
        }
        return toResponse(guardianId);
    }

    /** 미복용 요약을 계정 차원에서 <b>끈</b> 보호자. 행이 없으면 기본값 ON이라 포함되지 않는다. */
    @Transactional(readOnly = true)
    public Set<String> medicationDisabledGuardians(Collection<String> guardianIds) {
        if (guardianIds.isEmpty()) {
            return Set.of();
        }
        return repository.findByGuardianIdIn(guardianIds).stream()
                .filter(preference -> !preference.isMedicationEnabled())
                .map(GuardianNotificationPreference::getGuardianId)
                .collect(Collectors.toSet());
    }

    private GuardianNotificationTypeSettingResponse toResponse(String guardianId) {
        boolean medication = repository.findByGuardianId(guardianId)
                .map(GuardianNotificationPreference::isMedicationEnabled)
                .orElse(DEFAULT_MEDICATION_ENABLED);
        return new GuardianNotificationTypeSettingResponse(
                TypeSetting.locked(),
                TypeSetting.locked(),
                TypeSetting.optional(anomalySettingService.getSetting(guardianId).reviewReminderEnabled()),
                TypeSetting.optional(medication));
    }
}
