package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 알림 이력 요약 카드.
 *
 * <p>{@code total = delivered + failed + notSent}다. {@code delivered}는 문자 대체 발송을 <b>포함</b>하고
 * ({@code smsFallback}은 그중 건수), {@code notSent}는 실패가 아니다 - 정지 계정 차단·채널 꺼 둠은 의도된 동작이다.</p>
 */
@Schema(description = "관리자 알림 이력 요약")
public record AdminNotificationSummaryResponse(

        @Schema(description = "적용된 기간", example = "LAST_7_DAYS")
        AdminNotificationPeriod period,

        @Schema(description = "전체 건수 (수신자 1명 = 1건)", example = "11")
        long total,

        @Schema(description = "전송 완료 (문자 대체 발송 포함)", example = "7")
        long delivered,

        @Schema(description = "그중 문자 대체 발송", example = "1")
        long smsFallback,

        @Schema(description = "전송 실패", example = "2")
        long failed,

        @Schema(description = "발송 안 함 (이용 제한 계정·수신자가 채널을 꺼 둠 - 실패 아님)", example = "2")
        long notSent
) {
}
