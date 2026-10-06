package kr.silverbridge.main.global.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * refresh 토큰 HttpOnly 쿠키 설정 (XCUT-G31, auth.refresh-cookie.*).
 * 쿠키 속성을 환경별로 조정하고, 문제가 생기면 enabled=false로 기존(본문 전용) 동작으로 되돌린다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "auth.refresh-cookie")
public class RefreshCookieProperties {

    /** false면 쿠키를 내리지도 읽지도 않는다(롤백 스위치). 이때는 본문 호환이 항상 켜진 것으로 본다. */
    private boolean enabled = true;

    /** 쿠키 이름. FE가 예전에 만들던 careai_refresh_token(비 HttpOnly)과 겹치지 않게 다른 이름을 쓴다. */
    private String name = "careai_rt";

    /** HTTPS 전용 여부. 로컬 http 개발에서만 false. */
    private boolean secure = true;

    /** Lax 기본. Strict는 외부 링크(카카오 로그인 복귀 등) 진입 첫 요청에서 쿠키가 빠질 수 있다. */
    private String sameSite = "Lax";

    /** 갱신·로그아웃 경로로 좁힌다. /ws 등 다른 요청에는 쿠키가 실리지 않는다. */
    private String path = "/api/auth";

    /** true면 응답 본문 refreshToken을 계속 내리고, 요청 본문 refreshToken도 받는다(전환 기간). */
    private boolean bodyCompat = true;
}
