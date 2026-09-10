package kr.silverbridge.main.domain.user.event;

/**
 * 관리자가 회원의 역할(WARD ↔ GUARDIAN)을 바꿨을 때 발행한다.
 * <p>
 * access token은 발급 시점의 role 클레임으로 권한을 만들고 {@code JwtAuthenticationFilter}는 요청마다
 * DB를 읽지 않는다. 그래서 역할만 바꾸면 옛 역할의 토큰이 만료(30분)까지 {@code @PreAuthorize}를
 * 그대로 통과한다. 정지({@link UserRestrictedEvent})와 같은 무효화 경로를 태워 즉시 듣게 한다
 * (2026-09-10 점검 M-1).
 */
public record UserRoleChangedEvent(String userId) {}
