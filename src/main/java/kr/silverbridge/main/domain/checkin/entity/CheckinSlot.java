package kr.silverbridge.main.domain.checkin.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalTime;
import java.util.Optional;

/**
 * 안부 시각 다섯 개(2026-11-01). 값은 AI 계약({@code "09:00"} 등)과 DB 컬럼에 그대로 쓰인다.
 *
 * <p>질문 기본 문구는 단정하지 않고 짧게 - 어르신이 한 문장으로 답할 수 있는 물음이다.</p>
 */
public enum CheckinSlot {
    AT_09("09:00", 9, "좋은 아침이에요. 밤에 잠은 잘 주무셨어요?"),
    AT_12("12:00", 12, "점심은 맛있게 드셨어요?"),
    AT_15("15:00", 15, "오후에는 어떻게 보내고 계세요?"),
    AT_18("18:00", 18, "저녁 식사는 하셨어요? 오늘 하루 어떠셨어요?"),
    AT_21("21:00", 21, "오늘 하루 편안하셨어요? 몸 불편한 데는 없으세요?");

    private final String value;
    private final LocalTime time;
    private final String defaultQuestion;

    CheckinSlot(String value, int hour, String defaultQuestion) {
        this.value = value;
        this.time = LocalTime.of(hour, 0);
        this.defaultQuestion = defaultQuestion;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public LocalTime time() {
        return time;
    }

    public String defaultQuestion() {
        return defaultQuestion;
    }

    /** {@code "09:00"} 형식 값으로 찾는다. 모르는 값이면 비어 있음. */
    public static Optional<CheckinSlot> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (CheckinSlot slot : values()) {
            if (slot.value.equals(value)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /** JSON 요청 본문용 - 모르는 값은 null(호출부가 400으로 막는다). */
    @JsonCreator
    public static CheckinSlot ofJson(String value) {
        return fromValue(value).orElse(null);
    }

    /** 그 시각(KST) 기준 지금 열려 있는 시각 = 지금 이전의 가장 늦은 시각. 09:00 전이면 비어 있음. */
    public static Optional<CheckinSlot> current(LocalTime now) {
        CheckinSlot found = null;
        for (CheckinSlot slot : values()) {
            if (!now.isBefore(slot.time)) {
                found = slot;
            }
        }
        return Optional.ofNullable(found);
    }
}
