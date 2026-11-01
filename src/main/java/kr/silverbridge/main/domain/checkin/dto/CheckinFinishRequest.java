package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;

/** 본인이 고른 오늘 기분(선택). 본문 없이 보내도 된다. 끝난 이유는 서버가 정한다. */
public record CheckinFinishRequest(
        @Schema(description = "JOY·CALM·LONELY·LOW_ENERGY·WORRIED 중 하나 또는 null(UNDECIDED 불가)")
        CheckinEmotion selfReport) {
}
