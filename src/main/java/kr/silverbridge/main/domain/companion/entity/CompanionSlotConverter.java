package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB에는 {@code "09:00"} 형식으로 저장한다(CHECK 제약과 AI 계약 값이 같다). */
@Converter
public class CompanionSlotConverter implements AttributeConverter<CompanionSlot, String> {

    @Override
    public String convertToDatabaseColumn(CompanionSlot slot) {
        return slot == null ? null : slot.code();
    }

    @Override
    public CompanionSlot convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return CompanionSlot.fromCode(code)
                .orElseThrow(() -> new IllegalStateException("unknown companion slot"));
    }
}
