package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.util.List;

/** 시각별 안부 질문 5개(09·12·15·18·21시 순서). */
public record CheckinQuestionsResponse(List<Item> questions) {

    public record Item(
            @Schema(description = "시각", example = "09:00") CheckinSlot slot,
            @Schema(description = "질문(최대 200자)") String question,
            @Schema(description = "보호자가 정한 질문이면 true, 기본 질문이면 false") boolean custom) {
    }
}
