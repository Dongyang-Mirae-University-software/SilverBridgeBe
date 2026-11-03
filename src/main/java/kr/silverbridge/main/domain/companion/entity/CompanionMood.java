package kr.silverbridge.main.domain.companion.entity;

import java.util.Optional;

/**
 * 대화 1회의 기분(AI가 답한 턴 중 가장 많이 나온 값). AI는 한글(기쁨·평온·걱정·우울)로 주고 판단할 수 없으면 null이다.
 * 판단할 수 없음은 값을 만들지 않고 null로 둔다 - 0이나 "정상"으로 바꾸지 않는다(화면 문구 "확인할 수 없어요").
 */
public enum CompanionMood {
    JOY,
    CALM,
    WORRY,
    DEPRESSED;

    public static Optional<CompanionMood> fromAi(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim()) {
            case "기쁨" -> Optional.of(JOY);
            case "평온" -> Optional.of(CALM);
            case "걱정" -> Optional.of(WORRY);
            case "우울" -> Optional.of(DEPRESSED);
            default -> Optional.empty();
        };
    }
}
