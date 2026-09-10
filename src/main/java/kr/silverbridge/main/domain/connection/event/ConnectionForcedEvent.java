package kr.silverbridge.main.domain.connection.event;

/**
 * 관리자가 보호자-피보호자를 강제로 연결한 직후 발행된다.
 *
 * <p>일반 연결과 달리 <b>피보호자의 수락이 없었다</b>. 그 수락이 곧 동의이므로, 동의 없이 생긴 관계는
 * 최소한 당사자가 알아야 한다 - 그래서 <b>양쪽 모두</b>에게 알림을 보낸다.</p>
 *
 * <p>이름을 함께 싣는 이유는 리스너가 문구를 만들 때 다시 조회하지 않게 하기 위함이다
 * ({@link ConnectionRequestedEvent}와 같은 방식).</p>
 */
public record ConnectionForcedEvent(
        Long connectionId,
        String guardianId,
        String wardId,
        String guardianName,
        String wardName
) {}
