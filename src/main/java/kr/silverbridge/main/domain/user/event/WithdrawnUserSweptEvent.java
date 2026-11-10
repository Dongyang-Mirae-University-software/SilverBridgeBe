package kr.silverbridge.main.domain.user.event;

/**
 * 탈퇴 스윕(WithdrawnUserPurgeScheduler)이 잔여 INACTIVE 계정을 영구 삭제하기 직전에 발행한다 (2026-11-10 2차 점검 L-4).
 * <p>
 * 탈퇴 리스너가 한 번도 돌지 않은 계정(커밋 직후 재시작 등)은 토큰 무효화 키가 없고 열린 WS가 남는다.
 * 트랜잭션 밖에서 발행하므로 {@code @EventListener}로 동기 처리하며, 처리 실패가 purge를 막아서는 안 된다.
 */
public record WithdrawnUserSweptEvent(String userId) {}
