package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinSettingDto;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 피보호자 본인의 말벗 설정(표정 분석 동의). 본인만 읽고 바꾼다 - 보호자 경로는 없다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinSettingService {

    private final CheckinProperties properties;
    private final CheckinSettingRepository repository;

    @Transactional(readOnly = true)
    public CheckinSettingDto get(String wardId) {
        requireActive();
        return new CheckinSettingDto(faceConsent(wardId));
    }

    @Transactional
    public CheckinSettingDto update(String wardId, CheckinSettingDto request) {
        requireActive();
        boolean consent = Boolean.TRUE.equals(request.faceConsent());
        repository.findByWardId(wardId).ifPresentOrElse(
                setting -> setting.changeFaceConsent(consent),
                () -> repository.save(CheckinSetting.of(wardId, consent)));
        log.info("[CHECKIN] 표정 분석 동의 변경: wardId={}, faceConsent={}", wardId, consent);
        return new CheckinSettingDto(consent);
    }

    /** 저장값이 없으면 꺼짐(기본값). 대화 턴에서 사진을 AI로 보낼지 정한다. */
    @Transactional(readOnly = true)
    public boolean faceConsent(String wardId) {
        return repository.findByWardId(wardId).map(CheckinSetting::isFaceConsent).orElse(false);
    }

    private void requireActive() {
        if (!properties.isActive()) {
            throw new CustomException(ErrorCode.CHECKIN_DISABLED);
        }
    }
}
