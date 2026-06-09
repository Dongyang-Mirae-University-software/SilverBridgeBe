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

    private final SosEventRepository sosEventRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 피보호자 SOS 발생: 이력을 저장하고 커밋 후 ACTIVE 보호자 전원에게 긴급 알림을 보내도록 이벤트를 발행한다.
     *
     * @param wardId SOS를 발생시킨 피보호자 ID (인증 주체)
     * @return 저장된 이력 ID와 발생 시각
     */
    @Transactional
    public SosResponse trigger(String wardId) {
        User ward = userRepository.findById(wardId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        SosEvent sosEvent = sosEventRepository.save(SosEvent.builder().wardId(wardId).build());

        eventPublisher.publishEvent(new SosTriggeredEvent(wardId, sosEvent.getId(), ward.getName()));
        log.info("SOS 발생: sosEventId={}, wardId={}", sosEvent.getId(), wardId);

        return new SosResponse(sosEvent.getId(), sosEvent.getCreatedAt());
    }
}
