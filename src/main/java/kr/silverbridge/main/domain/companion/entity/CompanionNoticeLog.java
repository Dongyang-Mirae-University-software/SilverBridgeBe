package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 안부 말벗 주의 알림 발송 기록(V66). <b>행의 존재 = 그 날 그 피보호자의 주의 알림을 이미 보냈다</b>(선점 후 발송).
 * {@code (ward_id, notice_date)} UNIQUE가 하루 1번을 보장한다. 관리자 "기분 변화 알림 보냄" 집계에도 쓴다.
 */
@Entity
@Table(name = "companion_notice_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionNoticeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    @Column(name = "notice_date", nullable = false)
    private LocalDate noticeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompanionMood mood;

    @Column(nullable = false)
    private int days;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    private CompanionNoticeLog(String wardId, LocalDate noticeDate, CompanionMood mood, int days, OffsetDateTime sentAt) {
        this.wardId = wardId;
        this.noticeDate = noticeDate;
        this.mood = mood;
        this.days = days;
        this.sentAt = sentAt;
    }

    public static CompanionNoticeLog of(String wardId, LocalDate noticeDate, CompanionMood mood, int days,
                                        OffsetDateTime sentAt) {
        return new CompanionNoticeLog(wardId, noticeDate, mood, days, sentAt);
    }
}
