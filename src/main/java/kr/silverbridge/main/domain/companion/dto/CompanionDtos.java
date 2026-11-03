package kr.silverbridge.main.domain.companion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 안부 말벗 기기(피보호자) API 요청·응답. 대답 원문·받아 적은 글은 응답으로만 나가고 저장하지 않는다. */
public final class CompanionDtos {

    private CompanionDtos() {
    }

    @Schema(description = "글 대답")
    public record ReplyRequest(@Schema(description = "대답 글 1~500자") String text) {
    }

    @Schema(description = "오늘 안부 대화 현황")
    public record TodayResponse(
            @Schema(description = "KST 날짜", example = "2026-11-04") String date,
            @Schema(description = "지금 열 수 있는 시간대(09:00 형식). 없으면 null") String currentSlot,
            List<SlotStatus> slots) {
    }

    public record SlotStatus(
            @Schema(example = "09:00") String slot,
            @Schema(description = "UPCOMING(아직) / AVAILABLE(지금 가능) / IN_PROGRESS(진행 중) / DONE(마침) / MISSED(지나감)")
            String status,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null - 화면에는 '확인할 수 없어요'(0·정상으로 표시 금지)")
            String mood) {
    }

    @Schema(description = "열린 안부 대화")
    public record SessionResponse(
            @Schema(description = "대화 번호 - 이후 호출에 쓴다") long sessionId,
            @Schema(example = "09:00") String slot,
            @Schema(description = "첫 질문 문장(기기가 읽어 준다)") String question,
            @Schema(description = "최대 턴 수. 알 수 없으면 null") Integer maxTurns,
            @Schema(description = "이미 열려 있던 대화를 이어 가는 것이면 true") boolean resumed) {
    }

    @Schema(description = "한 턴 결과")
    public record TurnResponse(
            int turnNo,
            @Schema(description = "말벗 응답 문장(기기가 읽어 준다). 저장하지 않는다") String reply,
            @Schema(description = "true면 대화가 끝났다(결과는 서버가 이미 저장했다)") boolean ended,
            @Schema(description = "MAX_TURNS·NO_RESPONSE·USER_STOP. 진행 중이면 null") String endReason,
            @Schema(description = "음성 대답을 받아 적은 글(음성 턴만). 저장하지 않는다") String transcript) {
    }

    @Schema(description = "마친 대화 결과")
    public record ResultResponse(
            long sessionId,
            @Schema(example = "09:00") String slot,
            @Schema(description = "MAX_TURNS·NO_RESPONSE·USER_STOP") String endReason,
            @Schema(description = "JOY·CALM·WORRY·DEPRESSED. 판단할 수 없으면 null") String mood,
            int turnCount) {
    }
}
