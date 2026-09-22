package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.ChannelAttempt;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.entity.NotificationNotSentReason;
import kr.silverbridge.main.global.enums.Role;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 알림 이력 한 건 = 알림 1건 × 수신자 1명.
 *
 * <p>실패 사유 팝업은 별도 호출 없이 {@code channelResults}로 그린다. 표의 "채널" 칸은
 * {@code deliveredChannels}(전송 완료된 채널만)이다 - 비어 있으면 "-".</p>
 */
@Schema(description = "관리자용 알림 이력 항목(수신자 1명 단위)")
public record AdminNotificationItem(

        @Schema(description = "이력 ID", example = "1024")
        Long id,

        @Schema(description = "발송 시각")
        OffsetDateTime sentAt,

        @Schema(description = "알림 종류", example = "WARD_SOS")
        NotificationType type,

        @Schema(description = "알림 종류 표시 문구", example = "SOS")
        String typeLabel,

        @Schema(description = "카테고리(탭)", example = "SOS")
        AdminNotificationCategory category,

        @Schema(description = "관련 피보호자 ID. 연결 수락·거절·해제, 문의 답변, 판정 요약처럼 특정할 수 없으면 null", example = "A1B2C3")
        String wardId,

        @Schema(description = "관련 피보호자 이름 (wardId가 null이면 null)", example = "김영자")
        String wardName,

        @Schema(description = "알림 제목", example = "긴급 SOS")
        String title,

        @Schema(description = "알림 본문", example = "김영자님이 긴급 도움을 요청했습니다.")
        String body,

        @Schema(description = "수신자 ID", example = "G1H2I3")
        String recipientId,

        @Schema(description = "수신자 이름", example = "김성호")
        String recipientName,

        @Schema(description = "수신자 역할", example = "GUARDIAN")
        Role recipientRole,

        @Schema(description = "수신자가 관련 피보호자 본인인가 (화면의 \"본인\" 뱃지)", example = "false")
        boolean recipientIsWard,

        @Schema(description = "보호자가 피보호자에게 어떤 사람인지 (현재 ACTIVE 연결 기준, 없으면 null)", example = "아들")
        String relation,

        @Schema(description = "전송 완료된 채널만. 없으면 빈 배열(화면 \"-\")", example = "[\"SMS\"]")
        List<NotificationChannelType> deliveredChannels,

        @Schema(description = "전송 결과", example = "SMS_FALLBACK")
        NotificationLogResult result,

        @Schema(description = "보내지 않은 사유 (result=NOT_SENT일 때만)", example = "RESTRICTED_ACCOUNT")
        NotificationNotSentReason notSentReason,

        @Schema(description = "보내지 않은 사유 표시 문구", example = "이용 제한 계정")
        String notSentReasonLabel,

        @Schema(description = "시도한 채널별 결과 (사유 팝업용). 보내지 않았으면 빈 배열")
        List<ChannelResultItem> channelResults
) {

    @Schema(description = "채널 1건 시도 결과")
    public record ChannelResultItem(
            @Schema(description = "채널", example = "FCM")
            NotificationChannelType channel,

            @Schema(description = "결과", example = "FAILED", allowableValues = {"DELIVERED", "FAILED"})
            ChannelResult.Status status,

            @Schema(description = "실패 사유 (실패일 때만)", example = "NO_DEVICE")
            ChannelFailureReason reason,

            @Schema(description = "실패 사유 표시 문구", example = "등록된 기기 없음")
            String reasonLabel
    ) {
        static ChannelResultItem of(ChannelAttempt attempt) {
            return new ChannelResultItem(attempt.channel(), attempt.status(), attempt.reason(),
                    attempt.reason() == null ? null : attempt.reason().label());
        }
    }

    /**
     * @param wardName      관련 피보호자 이름(탈퇴·미특정이면 null)
     * @param recipientName 수신자 이름
     * @param recipientRole 수신자 역할
     * @param relation      (보호자, 피보호자) ACTIVE 연결의 관계 라벨. 없으면 null
     */
    public static AdminNotificationItem of(NotificationLog log, String wardName, String recipientName,
                                           Role recipientRole, String relation) {
        List<ChannelAttempt> attempts = log.getChannelResults() == null ? List.of() : log.getChannelResults();
        return new AdminNotificationItem(
                log.getId(),
                log.getCreatedAt(),
                log.getType(),
                NotificationTypeLabel.of(log.getType()),
                AdminNotificationCategory.of(log.getType()),
                log.getWardId(),
                wardName,
                log.getTitle(),
                log.getBody(),
                log.getRecipientId(),
                recipientName,
                recipientRole,
                log.getRecipientId().equals(log.getWardId()),
                relation,
                attempts.stream().filter(ChannelAttempt::delivered).map(ChannelAttempt::channel).toList(),
                log.getResult(),
                log.getNotSentReason(),
                log.getNotSentReason() == null ? null : log.getNotSentReason().label(),
                attempts.stream().map(ChannelResultItem::of).toList());
    }
}
