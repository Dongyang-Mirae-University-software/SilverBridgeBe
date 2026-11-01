package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.time.LocalDate;

/** 정리 결과(본인 화면용 최소 정보). 생활 신호·요약은 보호자 조회(2차)에서만 준다. */
public record CheckinFinishResponse(
        @Schema(example = "15:00") CheckinSlot slot,
        LocalDate checkDate,
        @Schema(description = "AI가 들은 정서(UNDECIDED = 판단 보류)") CheckinEmotion emotion,
        @Schema(description = "본인이 고른 기분") CheckinEmotion selfReport,
        CheckinEndReason endReason) {
}
