package kr.silverbridge.main.global.websocket;

import kr.silverbridge.main.global.enums.Status;

import java.util.Optional;

/**
 * WebSocket 발송 직전에 수신자 계정 상태를 확인하는 포트(ANOM-G10·ADMIN-G28).
 * <p>
 * 구현은 user 도메인이 제공한다. {@code global}이 {@code domain}을 import하지 않도록 인터페이스를
 * 이쪽에 두어 의존 방향을 <b>domain → global 단방향</b>으로 유지한다.
 */
public interface WebSocketRecipientStatusPort {

    /**
     * 수신자의 계정 상태. 사용자 행이 없으면 빈 값 - "정지"가 아니라 "알 수 없음"이다.
     *
     * @param userId 수신자 ID
     */
    Optional<Status> findStatus(String userId);
}
