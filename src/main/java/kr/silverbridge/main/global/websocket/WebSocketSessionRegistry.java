package kr.silverbridge.main.global.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자별로 열려 있는 WebSocket 세션 목록(AUTH-G19·ADMIN-G28 세션 부분).
 *
 * <p>정지·비밀번호 변경·역할 변경·탈퇴로 토큰을 무효화해도 이미 열린 STOMP 세션은 핸드셰이크 검사를 다시 받지 않아
 * 그대로 살아 있다. 서버가 그 세션을 찾아 닫으려면 "userId → 세션" 목록이 필요한데, 핸드셰이크가 Principal을 세우지
 * 않아 {@code SimpUserRegistry}로는 찾을 수 없다. Principal을 세우면 {@code convertAndSendToUser}·구독 인가 경로의
 * 동작이 바뀔 수 있어, 그 대신 이 자체 목록을 둔다(구독 인가는 계속 세션 속성 userId를 읽는다).</p>
 *
 * <p>등록·제거는 {@link UserSessionTrackingHandlerDecorator}가 연결 수립·종료 때 한다. 키는 핸드셰이크
 * attributes의 userId({@link JwtHandshakeInterceptor})다.</p>
 *
 * <p>⚠️ <b>인스턴스 메모리 목록이다.</b> 이 서비스는 단일 인스턴스로 운영되므로 클러스터 공유를 두지 않았다.
 * API 서버를 여러 대로 늘리면 다른 인스턴스에 붙은 세션은 닫히지 않는다(그때는 브로커 relay·공유 채널로 다시 설계).</p>
 */
@Component
public class WebSocketSessionRegistry {

    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(String userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        // 마지막 세션이 빠지면 키도 지운다 - 접속했다 나간 사용자 수만큼 빈 집합이 쌓이지 않게
        sessionsByUser.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /** 그 사용자의 현재 세션 스냅샷. 순회 중 연결이 끊겨도 안전하도록 복사본을 준다. */
    public List<WebSocketSession> sessionsOf(String userId) {
        if (userId == null) return List.of();
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        return sessions == null ? List.of() : List.copyOf(sessions);
    }
}
