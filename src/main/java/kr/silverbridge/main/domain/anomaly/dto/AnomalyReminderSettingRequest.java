package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 재촉 수신 설정 변경 요청.
 *
 * <p>{@code null}은 <b>"변경하지 않음"</b>이다(설정 API 공통 규약). 필수로 바꾸면 기존 프론트의
 * 부분 요청이 400으로 깨진다.</p>
 */
@Schema(description = "이상감지 판정 재촉 수신 설정")
public record AnomalyReminderSettingRequest(

        @Schema(description = "재촉 수신 여부 (null이면 변경하지 않음)", example = "false")
        Boolean reviewReminderEnabled) {
}
