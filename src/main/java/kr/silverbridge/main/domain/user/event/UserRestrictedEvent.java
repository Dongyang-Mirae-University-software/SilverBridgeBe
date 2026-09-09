package kr.silverbridge.main.domain.user.event;

/**
 * 관리자가 계정을 이용 제한(RESTRICTED)으로 바꿨을 때 발행한다.
 * <p>
 * 상태만 바꾸면 로그인·토큰 재발급은 막히지만 이미 발급된 access token이 만료(30분)까지 살아 있다
 * (JwtAuthenticationFilter는 요청마다 DB를 읽지 않는다). 정지가 즉시 듣게 하려면 탈퇴·비밀번호 변경과
 * 같은 무효화 경로를 타야 한다.
 */
public record UserRestrictedEvent(String userId) {}
