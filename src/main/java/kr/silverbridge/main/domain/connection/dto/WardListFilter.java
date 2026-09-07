package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.ConnectionStatus;

import java.util.List;

/**
 * 보호자 피보호자 목록 조회(GET /api/guardian/connection/select)의 상태 필터.
 *
 * <p>프론트의 상태 탭이 서버 기준으로 목록을 받도록 하기 위한 값이다. 탭 전환마다 API를 다시 호출하면
 * 클라이언트 필터링에서 생기던 캐시·카운트 불일치를 피할 수 있다.</p>
 *
 * <p>{@link ConnectionStatus}를 그대로 파라미터로 받지 않는 이유가 있다. 이 엔드포인트는 진행 중인 연결
 * (ACTIVE·PENDING)만 다루는데 전체 상태 enum을 받으면 {@code status=REFUSED} 같은 요청이 400이 아니라
 * 빈 배열로 응답돼, 호출자가 "거절된 연결이 0건"으로 정반대 해석을 하게 된다. 종료된 이력
 * (CANCELLED·REFUSED·DISCONNECTED)은 {@code GET /api/guardian/connection/requests}가 담당한다.</p>
 */
@Schema(description = "피보호자 목록 상태 필터 - 생략 시 ALL(진행 중인 연결 전부)")
public enum WardListFilter {

    /** 연결됨. */
    ACTIVE(List.of(ConnectionStatus.ACTIVE)),

    /** 수락 대기. */
    PENDING(List.of(ConnectionStatus.PENDING)),

    /**
     * 진행 중인 연결 전부(ACTIVE + PENDING) = 파라미터를 생략했을 때의 기본값.
     * 거절·취소·해제된 이력은 포함하지 않는다.
     */
    ALL(List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING));

    private final List<ConnectionStatus> statuses;

    WardListFilter(List<ConnectionStatus> statuses) {
        this.statuses = statuses;
    }

    /** 파라미터 미지정(null)은 기본값 ALL로 해석한다. */
    public static WardListFilter orDefault(WardListFilter filter) {
        return filter == null ? ALL : filter;
    }

    public List<ConnectionStatus> toStatuses() {
        return statuses;
    }
}
