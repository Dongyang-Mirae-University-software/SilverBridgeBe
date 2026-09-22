package kr.silverbridge.main.domain.notification.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 알림 발송 이력 - 알림 1건 × 수신자 1명 = 1행. 관리자 "알림 이력" 화면의 원본이다.
 *
 * <p>{@code NotificationDispatcher}가 발송을 마친 뒤 한 곳에서만 쓴다. 기존 {@code *_reminder_log}는
 * 중복 발송을 막으려고 <b>보내기 전에</b> 적는 선점 기록이라 역할이 다르다(결과가 없다).</p>
 *
 * <p>수신자·피보호자가 탈퇴하면 함께 삭제된다(FK CASCADE) - 탈퇴자 데이터를 붙들지 않는다.
 * 보관 기간이 지난 행은 {@code NotificationLogCleanupScheduler}가 지운다.</p>
 *
 * <p>WebSocket 실시간 이벤트와 인증번호 문자는 디스패처를 거치지 않아 여기 남지 않는다.</p>
 */
@Entity
@Table(name = "notification_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "recipient_id", nullable = false, length = 6)
    private String recipientId;

    /** 이 알림이 어느 피보호자에 관한 것인가. 연결 수락·거절·해제, 문의 답변, 판정 요약처럼 특정할 수 없으면 null. */
    @Column(name = "ward_id", length = 6)
    private String wardId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private NotificationLogResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "not_sent_reason", length = 30)
    private NotificationNotSentReason notSentReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_results", nullable = false, columnDefinition = "jsonb")
    private List<ChannelAttempt> channelResults;

    @Builder
    private NotificationLog(OffsetDateTime createdAt, NotificationType type, String recipientId, String wardId,
                            String title, String body, NotificationLogResult result,
                            NotificationNotSentReason notSentReason, List<ChannelAttempt> channelResults) {
        this.createdAt = createdAt;
        this.type = type;
        this.recipientId = recipientId;
        this.wardId = wardId;
        this.title = title;
        this.body = body;
        this.result = result;
        this.notSentReason = notSentReason;
        this.channelResults = channelResults == null ? List.of() : List.copyOf(channelResults);
    }
}
