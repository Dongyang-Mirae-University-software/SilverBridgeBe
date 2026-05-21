package kr.silverbridge.main.domain.user.port;

/**
 * user 도메인이 전화번호(SMS) 인증 완료 여부를 확인할 때 의존하는 포트 (B-1).
 * <p>
 * 구현은 auth 도메인이 제공한다({@code SmsService}). user 도메인이 auth 서비스를 직접 import 하지 않도록
 * 인터페이스를 user 쪽에 두어 의존 방향을 <b>auth → user 단방향</b>으로 정렬한다.
 * (auth는 이미 UserRepository·user 이벤트를 사용하므로 auth → user 방향만 남긴다.)
 */
public interface PhoneVerificationPort {

    /**
     * 전화번호 인증 nonce 일치를 검증하고 인증 키를 소비한다.
     * 인증 미완료/만료/nonce 불일치 시 예외를 던진다.
     *
     * @param phone 인증 대상 전화번호
     * @param nonce SMS 인증 확인 응답에서 발급된 nonce
     */
    void consumeVerification(String phone, String nonce);
}
