package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.dto.SosResponse;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.event.SosTriggeredEvent;
import kr.silverbridge.main.domain.sos.repository.SosEventRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 피보호자 긴급 SOS 처리 서비스.
 *
 * <p>SOS 발생 시 ① 이력(sos_events)을 저장하고 ② {@link SosTriggeredEvent}를 발행한다. 실제 보호자 알림 발송은
 * {@code SosNotificationListener}가 트랜잭션 커밋 후(AFTER_COMMIT) 담당하므로, 알림 발송이 실패하거나 느려도
 * 이력 저장은 롤백되지 않는다("이력은 무조건 남는다"). 보호자 조회·알림 발송 같은 부수효과를 이 트랜잭션에서
 * 분리해 SOS 응답을 즉시 반환한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SosService {

    /** 피보호자 이름이 비어 있을 때 알림 문구에 쓰는 폴백 — "null님이..." 같은 문구를 막는다. */
    private static final String FALLBACK_WARD_NAME = "보호 대상자";

    private final SosEventRepository sosEventRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 피보호자 SOS 발생: 이력을 저장하고 커밋 후 ACTIVE 보호자 전원에게 긴급 알림을 보내도록 이벤트를 발행한다.
     *
     * @param wardId   SOS를 발생시킨 피보호자 ID (인증 주체)
     * @param location 발생 위치 자유 문구. {@code null}·공백이면 위치 미상으로 기록한다 — 서버는 위치를 추정하지 않는다
     * @return 저장된 이력 ID와 발생 시각
     */
    @Transactional
    public SosResponse trigger(String wardId, String location) {
        User ward = userRepository.findById(wardId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        SosEvent sosEvent = sosEventRepository.save(SosEvent.builder()
                .wardId(wardId)
                .location(normalizeLocation(location))
                .build());

        String wardName = StringUtils.hasText(ward.getName()) ? ward.getName() : FALLBACK_WARD_NAME;
        eventPublisher.publishEvent(new SosTriggeredEvent(wardId, sosEvent.getId(), wardName));
        log.info("SOS 발생: sosEventId={}, wardId={}", sosEvent.getId(), wardId);

        return new SosResponse(sosEvent.getId(), sosEvent.getCreatedAt());
    }

    /**
     * 공백만 들어온 위치는 저장하지 않는다 — 이력 화면에 빈 "📍" 줄이 생기지 않게 한다.
     * (위치 문구는 알림 발송에 쓰이지 않으므로 여기서 정규화만 하고 통보 경로는 손대지 않는다.)
     */
    private String normalizeLocation(String location) {
        return StringUtils.hasText(location) ? location.trim() : null;
    }
}
