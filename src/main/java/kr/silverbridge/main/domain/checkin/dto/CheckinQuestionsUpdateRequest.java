package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.util.List;

/** 바꿀 시각만 보낸다(보내지 않은 시각은 그대로). 질문을 빈 문자열로 보내면 그 시각은 기본 질문으로 돌아간다. */
public record CheckinQuestionsUpdateRequest(
        @NotNull @Size(min = 1, max = 5) @Valid List<Item> questions) {

    public record Item(
            @Schema(description = "시각(09:00·12:00·15:00·18:00·21:00)", example = "09:00") @NotNull CheckinSlot slot,
            @Schema(description = "질문 1~200자, 빈 문자열이면 기본 질문으로") @NotNull String question) {
    }
}
