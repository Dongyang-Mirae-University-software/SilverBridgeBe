package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 턴 한 번의 결과. 받아쓴 글자·AI 답은 본인 화면용으로만 내려 주고 BE에 저장하지 않는다. */
public record CheckinTurnResponse(
        @Schema(description = "이번 턴 번호(1부터)") int turnNo,
        @Schema(description = "어르신 말씀을 받아쓴 글자(본인 확인용)") String transcript,
        @Schema(description = "말벗의 답(읽어 드릴 문장). 위험 신호가 있으면 119·109 고정 안내") String reply,
        @Schema(description = "남은 턴 수") int remainingTurns,
        @Schema(description = "더 말할 수 없어 정리(finish)만 남았으면 true") boolean finishRequired,
        @Schema(description = "응급 신호(119 안내가 나갔음)") boolean emergency,
        @Schema(description = "자해 신호(109 안내가 나갔음)") boolean selfHarm) {
}
