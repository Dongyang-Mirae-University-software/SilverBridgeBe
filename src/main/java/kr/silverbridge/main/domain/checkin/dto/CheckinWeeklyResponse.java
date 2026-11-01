package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.time.LocalDate;
import java.util.List;

/** 보호자용 7일 추세(끝 날짜 포함). 개수와 시각별 정서만 - 요약·근거는 하루 조회에서. 연결 전 기록은 세지 않는다. */
public record CheckinWeeklyResponse(String wardId, LocalDate from, LocalDate to, List<Day> days) {

    public record Day(
            LocalDate date,
            @Schema(description = "마친 대화 수(0~5)") int done,
            @Schema(description = "알림을 받고 응답하지 않은 시각 수") int missed,
            @Schema(description = "확인 필요 대화 수") int attention,
            @Schema(description = "위험 신호가 있었던 날") boolean risk,
            @Schema(description = "마친 대화의 시각별 정서") List<SlotEmotion> emotions) {
    }

    public record SlotEmotion(@Schema(example = "09:00") CheckinSlot slot, CheckinEmotion emotion) {
    }
}
