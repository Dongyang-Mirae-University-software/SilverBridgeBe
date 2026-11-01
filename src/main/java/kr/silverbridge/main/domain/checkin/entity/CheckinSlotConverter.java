package kr.silverbridge.main.domain.checkin.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@link CheckinSlot} ↔ DB {@code '09:00'}. 모르는 값은 예외(CHECK가 막으므로 생기면 데이터 손상이다). */
@Converter
public class CheckinSlotConverter implements AttributeConverter<CheckinSlot, String> {

    @Override
    public String convertToDatabaseColumn(CheckinSlot slot) {
        return slot == null ? null : slot.value();
    }

    @Override
    public CheckinSlot convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        return CheckinSlot.fromValue(value)
                .orElseThrow(() -> new IllegalStateException("unknown checkin slot"));
    }
}
