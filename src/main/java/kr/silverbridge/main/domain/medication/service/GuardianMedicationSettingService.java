package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.config.MedicationProperties;
import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import kr.silverbridge.main.domain.medication.repository.GuardianMedicationSettingRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 보호자가 <b>특정 피보호자에 대해</b> 받을 미복용 요약 설정.
 *
 * <p>피보호자 단위인 {@link MedicationSettingService}(복용 알림·재알림)와 축이 다르다 -
 * 저쪽은 "그 피보호자에게 무엇을 보낼지"라 보호자들이 공유하고, 이쪽은 "내가 무엇을 언제 받을지"라
 * 보호자마다 따로 가진다.</p>
 *
 * <p><b>기본값 ON</b>: 행이 없으면 받는다. 기본 OFF면 아무도 켜지 않아 기능이 죽고, 끌 수단이 없으면
 * 알림 피로로 앱 알림을 통째로 꺼 SOS·이상감지까지 함께 죽는다 - 켜두되 이것만 끌 수 있게 한다.</p>
 *
 * <p><b>기본 시각</b>은 저장하지 않고 {@link MedicationProperties}에서 읽는다. 미설정 행에 21:00을
 * 박아 두면 서버 기본값을 바꿔도 기존 보호자가 따라오지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianMedicationSettingService {

    private static final boolean DEFAULT_MISSED_ALERT_ENABLED = true;

    private final GuardianMedicationSettingRepository repository;
    private final ConnectionService connectionService;
    private final MedicationProperties properties;

    /** 설정이 없는 (보호자, 피보호자) 조합에 적용되는 실효값. */
    public GuardianMissedAlertSetting defaultSetting() {
        return new GuardianMissedAlertSetting(DEFAULT_MISSED_ALERT_ENABLED, defaultAlertTime());
    }

    /**
     * 보호자가 그 피보호자에 대해 가진 설정. 저장된 행이 없으면 기본값을 반환한다.
     *
     * @throws CustomException {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자
     */
    @Transactional(readOnly = true)
    public GuardianMissedAlertSetting getSetting(String guardianId, String wardId) {
        requireActiveConnection(guardianId, wardId, "미복용 요약 설정 조회");

        return repository.findByGuardianIdAndWardId(guardianId, wardId)
                .map(this::toEffective)
                .orElseGet(this::defaultSetting);
    }

    /**
     * 한 피보호자에 대한 보호자들의 설정을 한 번에 조회한다(발송 시 N+1 회피).
     * 저장된 행이 없는 보호자는 맵에 없으므로 호출자가 {@link #defaultSetting()}으로 채운다.
     *
     * <p>발송 스케줄러 전용이라 연결 검증을 하지 않는다 - 호출자가 이미 ACTIVE 보호자 목록으로
     * 좁혀 놓은 뒤 부른다.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, GuardianMissedAlertSetting> findSettings(String wardId, Collection<String> guardianIds) {
        if (guardianIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByWardIdAndGuardianIdIn(wardId, guardianIds).stream()
                .collect(Collectors.toMap(
                        GuardianMedicationSetting::getGuardianId,
                        this::toEffective));
    }

    /**
     * 보호자 한 명이 여러 피보호자에 대해 가진 설정을 한 번에 조회한다(카드 목록 렌더링용).
     * 저장된 행이 없는 피보호자는 맵에 없다.
     */
    @Transactional(readOnly = true)
    public Map<String, GuardianMissedAlertSetting> findSettingsOfGuardian(String guardianId,
                                                                          Collection<String> wardIds) {
        if (wardIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByGuardianIdAndWardIdIn(guardianId, wardIds).stream()
                .collect(Collectors.toMap(
                        GuardianMedicationSetting::getWardId,
                        this::toEffective));
    }

    /**
     * 설정을 upsert하고 적용된 실효값을 반환한다. {@code null} 필드는 변경하지 않는다.
     *
     * <p>시각을 기본값으로 되돌리려면 기본 시각을 직접 지정한다 - {@code null}은 이 API 공통 규약상
     * "변경하지 않음"이라 초기화 신호로 쓸 수 없다.</p>
     *
     * @throws CustomException {@code MEDICATION_NOT_AUTHORIZED} ACTIVE 연결이 아닌 피보호자
     */
    @Transactional
    public GuardianMissedAlertSetting update(String guardianId, String wardId,
                                             Boolean missedAlertEnabled, LocalTime missedAlertTime) {
        requireActiveConnection(guardianId, wardId, "미복용 요약 설정 변경");

        GuardianMedicationSetting setting = repository.findByGuardianIdAndWardId(guardianId, wardId)
                .orElseGet(() -> repository.save(GuardianMedicationSetting.of(
                        guardianId, wardId, DEFAULT_MISSED_ALERT_ENABLED)));

        if (missedAlertEnabled != null) {
            setting.updateMissedAlertEnabled(missedAlertEnabled);
        }
        if (missedAlertTime != null) {
            setting.updateMissedAlertTime(missedAlertTime);
        }
        return toEffective(setting);
    }

    /**
     * ACTIVE 연결이 아니면 403 + {@code [IDOR-ATTEMPT]} WARN.
     *
     * <p>V40까지 이 설정은 보호자 계정 단위라 대상이 본인뿐이었지만, 피보호자별로 나뉘면서
     * <b>남의 피보호자 설정을 건드릴 수 있는 경로</b>가 되었다. 복약 도메인 규칙과 같은 형태로 막는다
     * ({@code getMyWards}는 PENDING이 섞여 있어 인가 목록으로 쓰지 않는다).</p>
     */
    private void requireActiveConnection(String guardianId, String wardId, String action) {
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 복약 설정 접근 시도: guardianId={}, wardId={}, action={}",
                    guardianId, wardId, action);
            throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        }
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
