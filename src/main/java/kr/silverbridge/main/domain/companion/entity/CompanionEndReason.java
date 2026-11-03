package kr.silverbridge.main.domain.companion.entity;

import java.util.Optional;

/**
 * 대화가 끝난 이유. AI는 한글 문구({@code 최대 턴 수}·{@code 연속 무응답})와 {@code USER_STOP}을 섞어 주므로
 * 백엔드가 영문 코드로 바꿔 저장한다(결정 D). 모르는 값은 {@link Optional#empty()} - 호출부가 기본값을 정한다.
 */
public enum CompanionEndReason {
    MAX_TURNS,
    NO_RESPONSE,
    USER_STOP;

    public static Optional<CompanionEndReason> fromAi(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim()) {
            case "최대 턴 수", "MAX_TURNS" -> Optional.of(MAX_TURNS);
            case "연속 무응답", "NO_RESPONSE" -> Optional.of(NO_RESPONSE);
            case "USER_STOP" -> Optional.of(USER_STOP);
            default -> Optional.empty();
        };
    }
}
