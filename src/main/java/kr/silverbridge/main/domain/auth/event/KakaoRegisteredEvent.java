package kr.silverbridge.main.domain.auth.event;

/**
 * 카카오 신규 회원가입이 완료(트랜잭션 커밋)되었음을 알리는 이벤트.
 *
 * <p>가입 트랜잭션 안에서 KAKAO_LOGIN 접속로그를 REQUIRES_NEW로 남기면,
 * 아직 커밋되지 않은 users 행을 별도 트랜잭션이 보지 못해 FK 위반(SQLState 23503)이 발생한다.
 * 따라서 접속로그 기록은 이 이벤트의 AFTER_COMMIT 리스너로 미뤄 user 행이 커밋된 뒤에 수행한다.
 * 가입이 롤백되면 이벤트가 발화하지 않아 실패한 가입의 로그도 남지 않는다(정상).
 */
public record KakaoRegisteredEvent(
        String userId,
        String ipAddress,
        String userAgent
) {}
