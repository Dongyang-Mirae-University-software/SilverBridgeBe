package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;

import java.time.LocalDate;
import java.util.List;

/**
 * 보호자용 하루 안부 결과. <b>받아쓴 원문은 없다</b>(2026-11-01 결정 Q3) - AI의 고정 요약 문장과 근거(신호·턴 번호)만.
 * 지금 연결이 맺어지기 전 기록은 {@code BEFORE_CONNECTION}으로 내용 없이 내려 준다(D-1).
 */
public record CheckinDailyResponse(
        String wardId,
        LocalDate date,
        @Schema(description = "그날 확인 필요(평소와 다름·위험)가 하나라도 있으면 true") boolean attention,
        List<SlotItem> slots) {

    public enum SlotStatus {
        /** 대화를 마쳤다 - 결과 칸이 채워진다. */
        DONE,
        /** 지금 대화 중. */
        IN_PROGRESS,
        /** 응답 창이 열려 있고 아직 대화하지 않았다. */
        OPEN,
        /** 아직 시각이 오지 않았다. */
        UPCOMING,
        /** 안부 알림을 받았지만 응답 창 안에 대화하지 않았다. */
        MISSED,
        /** 알림을 보내지 않았고 대화도 없다(알림 꺼짐 등). */
        NO_RECORD,
        /** 지금 연결이 맺어지기 전 시각이라 보이지 않는다. */
        BEFORE_CONNECTION
    }

    public record SlotItem(
            @Schema(example = "09:00") CheckinSlot slot,
            SlotStatus status,
            @Schema(description = "AI가 들은 정서(UNDECIDED = 판단 보류)") CheckinEmotion emotion,
            @Schema(description = "본인이 고른 기분") CheckinEmotion selfReport,
            Signals signals,
            @Schema(description = "응급·자해 위험 신호가 있었음") boolean risk,
            @Schema(description = "평소 대비 확인 필요") boolean attention,
            @Schema(description = "확인 필요 근거 코드(RISK·SLEEP_UNUSUAL·MEAL_UNUSUAL·PAIN_UNUSUAL·MOOD_UNUSUAL·"
                    + "LONELY_UNUSUAL·SELF_REPORT_NEGATIVE·EMOTION_UNUSUAL·VOICE_CHANGE)") List<String> attentionReasons,
            @Schema(description = "AI 요약(단정하지 않는 고정 문장, 대화 원문 아님)") String summary,
            @Schema(description = "근거 - 어느 신호를 몇 번째 턴에서 들었는지") List<CheckinResult.Evidence> evidence) {

        public static SlotItem empty(CheckinSlot slot, SlotStatus status) {
            return new SlotItem(slot, status, null, null, null, false, false, List.of(), null, List.of());
        }
    }

    @Schema(description = "생활 신호(모르면 null). sleep GOOD/BAD · meal ATE/SKIPPED · pain YES/NO · mood GOOD/NORMAL/BAD · lonely YES/NO")
    public record Signals(String sleep, String meal, String pain, String mood, String lonely) {
    }
}
