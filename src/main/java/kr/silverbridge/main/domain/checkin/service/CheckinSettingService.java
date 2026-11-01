package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinGuardianSettingDto;
import kr.silverbridge.main.domain.checkin.dto.CheckinWardSettingRequest;
import kr.silverbridge.main.domain.checkin.dto.CheckinWardSettingResponse;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 말벗 설정(피보호자 단위 한 행). 표정 분석 동의는 <b>본인만</b> 바꾼다(보호자 경로 없음).
 * 안부 알림 받기는 보호자(ACTIVE 연결)가 켜고 끄며, 본인도 끌 수 있다 - 하루 다섯 번 오는 푸시를 본인이 멈출 수 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinSettingService {

    private final CheckinProperties properties;
    private final CheckinSettingRepository repository;
    private final CheckinGuardianAccess access;

    @Transactional(readOnly = true)
    public CheckinWardSettingResponse getForWard(String wardId) {
        requireActive();
        return toWardResponse(repository.findByWardId(wardId).orElse(null));
    }

    @Transactional
    public CheckinWardSettingResponse updateForWard(String wardId, CheckinWardSettingRequest request) {
        requireActive();
        if (request == null || (request.faceConsent() == null && request.promptEnabled() == null)) {
            throw new CustomException(ErrorCode.CHECKIN_INVALID_SETTING);
        }
        CheckinSetting setting = settingOf(wardId);
        if (request.faceConsent() != null) {
            setting.changeFaceConsent(request.faceConsent());
        }
        if (request.promptEnabled() != null) {
            setting.changePromptEnabled(request.promptEnabled(), wardId);
        }
        log.info("[CHECKIN] 피보호자 말벗 설정 변경: wardId={}, faceConsent={}, promptEnabled={}",
                wardId, setting.isFaceConsent(), setting.isPromptEnabled());
        return toWardResponse(setting);
    }

    @Transactional(readOnly = true)
    public CheckinGuardianSettingDto getForGuardian(String guardianId, String wardId) {
        requireActive();
        access.require(guardianId, wardId, "setting-get");
        return new CheckinGuardianSettingDto(
                repository.findByWardId(wardId).map(CheckinSetting::isPromptEnabled).orElse(false));
    }

    @Transactional
    public CheckinGuardianSettingDto updateForGuardian(String guardianId, String wardId,
                                                       CheckinGuardianSettingDto request) {
        requireActive();
        access.require(guardianId, wardId, "setting-update");
        CheckinSetting setting = settingOf(wardId);
        setting.changePromptEnabled(Boolean.TRUE.equals(request.promptEnabled()), guardianId);
        log.info("[CHECKIN] 안부 알림 받기 변경(보호자): guardianId={}, wardId={}, promptEnabled={}",
                guardianId, wardId, setting.isPromptEnabled());
        return new CheckinGuardianSettingDto(setting.isPromptEnabled());
    }

    /**
     * 연결이 끝난 보호자가 켜 둔 안부 알림을 끈다(2026-11-01 점검 L-1). 남은 보호자는 켠 적이 없을 수 있어, 모르는 사이
     * 하루 다섯 번 알림이 계속 가지 않게 한다. 다른 보호자·본인이 켠 설정은 건드리지 않는다. 동기 AFTER_COMMIT 리스너가 부르므로
     * {@code REQUIRES_NEW}(기본 전파면 이미 커밋된 트랜잭션에 합류해 쓰기가 사라진다 - H-1).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int disablePromptEnabledBy(String guardianId, String wardId) {
        return repository.disablePromptEnabledBy(wardId, guardianId);
    }

    /** 저장값이 없으면 꺼짐(기본값). 대화 턴에서 사진을 AI로 보낼지 정한다. */
    @Transactional(readOnly = true)
    public boolean faceConsent(String wardId) {
        return repository.findByWardId(wardId).map(CheckinSetting::isFaceConsent).orElse(false);
    }

    private CheckinSetting settingOf(String wardId) {
        return repository.findByWardId(wardId)
                .orElseGet(() -> repository.save(CheckinSetting.of(wardId, false)));
    }

    private static CheckinWardSettingResponse toWardResponse(CheckinSetting setting) {
        return setting == null
                ? new CheckinWardSettingResponse(false, false)
                : new CheckinWardSettingResponse(setting.isFaceConsent(), setting.isPromptEnabled());
    }

    private void requireActive() {
        if (!properties.isActive()) {
            throw new CustomException(ErrorCode.CHECKIN_DISABLED);
        }
    }
}
