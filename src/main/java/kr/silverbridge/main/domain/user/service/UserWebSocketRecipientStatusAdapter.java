package kr.silverbridge.main.domain.user.service;

import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.websocket.WebSocketRecipientStatusPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * {@link WebSocketRecipientStatusPort} 구현 - PK로 상태 컬럼만 읽는다.
 *
 * <p>캐시를 두지 않는다. 정지 직후의 이벤트부터 막는 것(즉시성)이 이 확인의 목적이라,
 * 짧은 TTL이라도 그 사이 정지 계정에 실시간 알림이 새어 나간다.</p>
 */
@Component
@RequiredArgsConstructor
public class UserWebSocketRecipientStatusAdapter implements WebSocketRecipientStatusPort {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Status> findStatus(String userId) {
        return userRepository.findStatusById(userId);
    }
}
