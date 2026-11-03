package kr.silverbridge.main.domain.companion.entity;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Optional;

/**
 * 안부 말벗 시간대 5개(2026-11-04). 저장·AI 전송 값은 {@link #code()}({@code "09:00"} 형식)이다 - AI 계약의
 * {@code slot} 값과 같다. 각 시각부터 {@code companion.window}(기본 60분) 동안만 대화를 연다.
 */
public enum CompanionSlot {

    S09("09:00", LocalTime.of(9, 0)),
    S12("12:00", LocalTime.of(12, 0)),
    S15("15:00", LocalTime.of(15, 0)),
    S18("18:00", LocalTime.of(18, 0)),
    S21("21:00", LocalTime.of(21, 0));

    private final String code;
    private final LocalTime start;

    CompanionSlot(String code, LocalTime start) {
        this.code = code;
        this.start = start;
    }

    public String code() {
        return code;
    }

    public LocalTime start() {
        return start;
    }

    public static Optional<CompanionSlot> fromCode(String code) {
        return Arrays.stream(values()).filter(slot -> slot.code.equals(code)).findFirst();
    }

    /** {@code time}이 이 시간대의 대화 창(시작 시각 이상, 시작 + windowMinutes 미만) 안인지. */
    public boolean contains(LocalTime time, long windowMinutes) {
        return !time.isBefore(start) && time.isBefore(start.plusMinutes(windowMinutes));
    }
}
