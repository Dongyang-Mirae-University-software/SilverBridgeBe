package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingResponse;
import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import kr.silverbridge.main.domain.anomaly.repository.GuardianAnomalySettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자별 재촉 수신 설정 조회·변경.
 *
 * <p>행이 없으면 <b>기본값 ON</b>이다 - 기존 사용자 백필이 필요 없고, 끄겠다고 누른 사람만 행이 생긴다.</p>
 *
 * <p>이 설정을 없애거나 강제 채널로 승격시키지 말 것 - 보호자가 이 알림만 끌 수 없으면 앱 알림을
 * 통째로 꺼버리고, 그때 SOS·이상감지 같은 필수 알림까지 함께 죽는다.</p>
 */
@Service
@RequiredArgsConstructor
public class GuardianAnomalySettingService {

    /** 저장된 행이 없는 보호자에게 적용되는 기본값. */
    private static final boolean DEFAULT_REVIEW_REMINDER_ENABLED = true;

    private final GuardianAnomalySettingRepository settingRepository;

    @Transactional(readOnly = true)
    public AnomalyReminderSettingResponse getSetting(String guardianId) {
        return new AnomalyReminderSettingResponse(settingRepository.findByGuardianId(guardianId)
                .map(GuardianAnomalySetting::isReviewReminderEnabled)
                .orElse(DEFAULT_REVIEW_REMINDER_ENABLED));
    }

    /**
     * 설정 변경. {@code null}은 "변경하지 않음"이라 현재값을 그대로 돌려준다.
     */
    @Transactional
    public AnomalyReminderSettingResponse updateSetting(String guardianId, Boolean reviewReminderEnabled) {
        if (reviewReminderEnabled == null) {
            return getSetting(guardianId);
        }

        GuardianAnomalySetting setting = settingRepository.findByGuardianId(guardianId)
                .orElseGet(() -> settingRepository.save(GuardianAnomalySetting.builder()
                        .guardianId(guardianId)
                        .reviewReminderEnabled(DEFAULT_REVIEW_REMINDER_ENABLED)
                        .build()));
        setting.changeReviewReminderEnabled(reviewReminderEnabled);

        return new AnomalyReminderSettingResponse(setting.isReviewReminderEnabled());
    }
}
