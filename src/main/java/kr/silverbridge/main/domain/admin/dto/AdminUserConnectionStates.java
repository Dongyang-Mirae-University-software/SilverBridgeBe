package kr.silverbridge.main.domain.admin.dto;

import kr.silverbridge.main.global.enums.ConnectionStatus;

import java.util.List;

/**
 * 연결 목록에서 표시용 연결 상태를 고른다. 필터({@link AdminUserConnectionFilter})의 CASE 식과 같은 우선순위다 -
 * ACTIVE가 하나라도 있으면 CONNECTED, 없고 PENDING만 있으면 PENDING, 둘 다 없으면 NONE.
 *
 * <p>규칙이 두 곳(JPQL·자바)에 나뉘어 있으므로 한쪽만 바꾸지 말 것 - 어긋나면 "연결됨으로 걸렀는데
 * 목록에는 수락 대기로 표시"되는 모순이 생긴다.</p>
 */
final class AdminUserConnectionStates {

    private AdminUserConnectionStates() {
    }

    static AdminUserConnectionState of(List<AdminUserConnectionItem> connections) {
        boolean hasActive = connections.stream()
                .anyMatch(connection -> connection.status() == ConnectionStatus.ACTIVE);
        if (hasActive) {
            return AdminUserConnectionState.CONNECTED;
        }
        return connections.isEmpty() ? AdminUserConnectionState.NONE : AdminUserConnectionState.PENDING;
    }
}
