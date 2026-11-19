package kr.silverbridge.main.domain.game.service;

/**
 * 회원 ID → AI 게임 사용자 번호. AI games API가 {@code userId}를 정수로만 받아서 쓰는 변환이다.
 *
 * <p>FE {@code src/utils/game/userId.ts}의 {@code toGameUserId}와 <b>같은 값</b>을 내야 한다 - 피보호자 게임 화면(iframe)이
 * 그 함수로 기록을 남기고, 보호자 조회는 여기서 같은 번호를 만들어 읽는다. 식은 {@code hash = hash*31 + charCode}(32비트로 접음)
 * = Java {@link String#hashCode()}이고, 절댓값이 0이면 1이다.</p>
 *
 * <p>{@code long}으로 계산한다 - JS {@code Math.abs(-2^31)}은 2147483648이지만 Java {@code Math.abs(Integer.MIN_VALUE)}는 음수로 남는다.</p>
 *
 * <p>이 번호는 클라이언트에서 받지 않는다(받으면 남의 기록을 조회할 수 있다). AI user_id를 문자열로 바꾸면 FE 함수와 함께 지운다.</p>
 */
public final class GameUserIds {

    private GameUserIds() {
    }

    public static long of(String userId) {
        return fromHash(userId.hashCode());
    }

    static long fromHash(int hash) {
        long abs = Math.abs((long) hash);
        return abs == 0 ? 1 : abs;
    }
}
