package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record CheckinSessionResponse(
        @Schema(description = "세션 ID(무작위, 턴·정리 경로에 쓴다)") String sessionId,
        @Schema(description = "이번 시각", example = "15:00") CheckinSlot slot,
        @Schema(description = "날짜(KST)") LocalDate checkDate,
        @Schema(description = "어르신께 읽어 드릴 첫 질문") String question,
        @Schema(description = "지금까지 주고받은 턴 수") int turnCount,
        @Schema(description = "최대 턴 수") int maxTurns,
        @Schema(description = "대화가 끝나는 시각(KST). 이후에는 정리만 할 수 있다") OffsetDateTime endsAt,
        @Schema(description = "진행 중이던 대화를 이어받았으면 true") boolean resumed,
        @Schema(description = "더 말할 수 없어 정리(finish)만 남았으면 true") boolean finishRequired) {
}
