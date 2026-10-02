package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.dto.SosSettingResponse;
import kr.silverbridge.main.domain.sos.dto.SosSettingUpdateRequest;
import kr.silverbridge.main.domain.sos.entity.SosAction;
import kr.silverbridge.main.domain.sos.entity.SosSetting;
import kr.silverbridge.main.domain.sos.repository.SosSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 피보호자 SOS 동작 설정 조회/변경 서비스.
 *
 * <p><b>기본값 정책</b>: 설정 행이 없는 사용자는 {@link #DEFAULT_SOS_ACTION}을 따른다. 프론트가 쓰던
 * 기본값({@code call119AndNotify})과 같은 값이라 계정 동기화 전환 후에도 동작이 바뀌지 않고,
 * 기존 사용자 백필 마이그레이션도 필요 없다({@code NotificationSettingService}와 동일한 방식).</p>
 *
 * <p>이 설정은 <b>프론트의 119 안내 화면 흐름</b>만 정한다(실제 발신은 하지 않는다 - {@link SosAction} 참조).
 * 보호자 알림 발송 경로
 * ({@code SosNotificationListener} → {@code NotificationType.WARD_SOS} 강제 발송)는 이 값을 읽지 않으며,
 * 어떤 값이든 보호자 알림은 항상 나간다. 자세한 배경은 {@link SosAction} 참조.</p>
 */
@Service
@RequiredArgsConstructor
public class SosSettingService {

    /** 설정 행이 없을 때 적용되는 기본 동작. 프론트 기존 기본값과 동일(119 화면 + 보호자 알림 안내). */
    private static final SosAction DEFAULT_SOS_ACTION = SosAction.CALL_119_AND_NOTIFY;

    private final SosSettingRepository repository;

    /** 현재 설정을 조회한다. 저장된 행이 없으면 기본값을 반환한다. */
    @Transactional(readOnly = true)
    public SosSettingResponse getSetting(String userId) {
        SosAction action = repository.findByUserId(userId)
                .map(SosSetting::getSosAction)
                .orElse(DEFAULT_SOS_ACTION);
        return SosSettingResponse.of(action);
    }

    /**
     * 설정을 upsert하고 갱신된 값을 반환한다.
     *
     * <p>행이 없으면 {@code INSERT ... ON CONFLICT DO NOTHING}으로 넣는다(SOS-G17). 동시 최초 저장으로 다른 요청이
     * 먼저 넣었으면(0건) 그 행을 다시 읽어 이 요청의 값으로 갱신한다 - 두 요청 모두 성공하고 나중 값이 남는다.</p>
     */
    @Transactional
    public SosSettingResponse updateSetting(String userId, SosSettingUpdateRequest request) {
        SosAction action = request.sosAction();
        Optional<SosSetting> existing = repository.findByUserId(userId);
        if (existing.isPresent()) {
            existing.get().updateSosAction(action);
        } else if (repository.insertIfAbsent(userId, action.name()) == 0) {
            // 동시 요청이 먼저 넣었다 - 커밋된 그 행을 읽어 갱신한다(READ COMMITTED라 새 문장에서 보인다)
            repository.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException("SOS 설정 동시 저장 후 행을 찾지 못함: " + userId))
                    .updateSosAction(action);
        }
        return SosSettingResponse.of(action);
    }
}
