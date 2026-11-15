package kr.silverbridge.main.domain.auth.event;

/**
 * 로그아웃이 커밋되었음을 알리는 이벤트 - 그 access 토큰으로 열어 둔 WebSocket 세션을 서버가 닫는 데 쓴다.
 *
 * <p>로그아웃은 그 access 토큰 하나만 블랙리스트에 올린다(다른 기기의 access 토큰은 만료까지 유효). 그래서 닫는 범위도
 * 그 토큰으로 연 세션뿐이다 - 사용자 전체(closeAll)를 닫으면 아직 유효한 다른 기기까지 1008 로 로그아웃된다
 * (최종 QA NOTI-A60, 2026-11-15 사용자 결정).</p>
 *
 * @param accessTokenHash {@code JwtTokenProvider.hashToken} 값. 토큰 원문은 싣지 않는다
 */
public record UserLoggedOutEvent(
        String userId,
        String accessTokenHash
) {}
