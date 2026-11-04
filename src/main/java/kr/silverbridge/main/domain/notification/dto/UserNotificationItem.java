package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;

import java.time.OffsetDateTime;

/**
 * 내 알림 기록 한 건. 발송 결과·채널별 결과·보내지 않은 사유는 관리자용이라 싣지 않는다.
 */
@Schema(description = "내 알림 한 건")
public record UserNotificationItem(
        @Schema(description = "알림 기록 ID (읽음 처리에 쓴다)", example = "1024")
        Long id,
        @Schema(description = "알림 종류 (NotificationType 이름)", example = "WARD_SOS")
        NotificationType type,
        @Schema(description = "화면 필터 묶음", example = "URGENT")
        UserNotificationCategory category,
        @Schema(description = "휴대폰에 표시된 제목과 같다", example = "긴급 SOS")
        String title,
        @Schema(description = "휴대폰에 표시된 본문과 같다(줄바꿈 \\n 포함 가능)")
        String body,
        @Schema(description = "발송 시각")
        OffsetDateTime createdAt,
        @Schema(description = "읽었는가")
        boolean read,
        @Schema(description = "이 알림이 관한 피보호자 ID - 다른 사람(피보호자)에 관한 알림일 때만. 본인에 관한 알림·문의 답변·판정 하루 요약은 null",
                example = "WD0001", nullable = true)
        String wardId,
        @Schema(description = "그 피보호자의 이름 (wardId가 있을 때)", example = "김영자", nullable = true)
        String wardName
) {

    /**
     * @param wardName 피보호자 이름. 수신자 본인에 관한 알림이면 쓰지 않는다
     */
    public static UserNotificationItem of(NotificationLog log, String wardName) {
        boolean aboutOther = log.getWardId() != null && !log.getWardId().equals(log.getRecipientId());
        return new UserNotificationItem(
                log.getId(),
                log.getType(),
                UserNotificationCategory.of(log.getType()),
                log.getTitle(),
                log.getBody(),
                log.getCreatedAt(),
                log.getReadAt() != null,
                aboutOther ? log.getWardId() : null,
                aboutOther ? wardName : null);
    }
}
