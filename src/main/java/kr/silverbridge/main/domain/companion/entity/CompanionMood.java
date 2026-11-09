package kr.silverbridge.main.domain.companion.entity;

import java.util.Optional;

/**
 * 대화 1회의 기분(AI가 답한 턴 중 가장 많이 나온 값). AI는 영문 코드(JOY·CALM·WORRY·DEPRESSED, 판단할 수 없으면
 * UNKNOWN)로 준다. 옛 AI는 한글(기쁨·평온·걱정·우울)을 줬으므로 배포 순서가 바뀌어도 되도록 한글도 계속 받는다.
 * 판단할 수 없음은 값을 만들지 않고 null로 둔다 - UNKNOWN·0·"정상"으로 바꾸지 않는다(화면 문구 "확인할 수 없어요").
 */
public enum CompanionMood {
    JOY,
    CALM,
    WORRY,
    DEPRESSED;

    /** 영문 코드는 대문자만 받는다(AI 계약). UNKNOWN·빈 값·모르는 값은 empty. */
    public static Optional<CompanionMood> fromAi(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim()) {
            case "JOY", "기쁨" -> Optional.of(JOY);
            case "CALM", "평온" -> Optional.of(CALM);
            case "WORRY", "걱정" -> Optional.of(WORRY);
            case "DEPRESSED", "우울" -> Optional.of(DEPRESSED);
            default -> Optional.empty();
        };
    }
}
