package kr.silverbridge.main.domain.checkin.entity;

import java.util.Optional;

/**
 * 정서 5가지 + 판단 보류(AI 계약 2026-11-01). API·DB는 영문 코드, AI와는 한글 값으로 주고받는다.
 * "확인 필요"는 정서가 아니라 BE가 평소 대비로 붙이는 표시라 여기 두지 않는다(2차).
 */
public enum CheckinEmotion {
    JOY("기쁨"),
    CALM("편안"),
    LONELY("외로움"),
    LOW_ENERGY("기운 없음"),
    WORRIED("걱정"),
    /** AI가 정서를 정하지 못함. 본인 응답(selfReport)으로는 고를 수 없다. */
    UNDECIDED("판단 보류");

    private final String aiValue;

    CheckinEmotion(String aiValue) {
        this.aiValue = aiValue;
    }

    public String aiValue() {
        return aiValue;
    }

    public boolean selectableBySelf() {
        return this != UNDECIDED;
    }

    public static Optional<CheckinEmotion> fromAiValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (CheckinEmotion emotion : values()) {
            if (emotion.aiValue.equals(value)) {
                return Optional.of(emotion);
            }
        }
        return Optional.empty();
    }
}
