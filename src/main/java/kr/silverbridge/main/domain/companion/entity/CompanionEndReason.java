package kr.silverbridge.main.domain.companion.entity;

import java.util.Optional;

/**
 * 대화가 끝난 이유. AI는 한글 문구({@code 최대 턴 수}·{@code 연속 무응답})와 {@code USER_STOP}을 섞어 주므로
 * 백엔드가 영문 코드로 바꿔 저장한다(결정 D). 모르는 값은 {@link Optional#empty()} - 호출부가 기본값을 정한다.
 *
 * <p>{@code AVOIDED}(2026-11-12 사용자 결정): 어르신이 이야기를 원하지 않아(한 대화에서 두 번 말을 피함) 말벗이
 * 마무리한 대화. 끝내기를 직접 누른 {@code USER_STOP}과 섞이지 않게 나눈다. 값을 더하면 CHECK 재정의가 필요하다
 * ({@code CompanionCheckSyncTest}).</p>
 */
public enum CompanionEndReason {
    MAX_TURNS,
    NO_RESPONSE,
    USER_STOP,
    AVOIDED;

    public static Optional<CompanionEndReason> fromAi(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim()) {
            case "최대 턴 수", "MAX_TURNS" -> Optional.of(MAX_TURNS);
            case "연속 무응답", "NO_RESPONSE" -> Optional.of(NO_RESPONSE);
            case "USER_STOP" -> Optional.of(USER_STOP);
            case "AVOIDED" -> Optional.of(AVOIDED);
            default -> Optional.empty();
        };
    }
}
