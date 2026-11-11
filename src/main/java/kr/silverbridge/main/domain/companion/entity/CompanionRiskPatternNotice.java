package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 여러 날 위험 누적 알림의 피보호자별 마지막 발송(V70, 2026-11-11). 피보호자당 1행이고 선점은 저장소의 조건부 UPSERT
 * ({@code CompanionRiskPatternNoticeRepository.claim})가 한 문장으로 한다 - 이 엔티티로 직접 저장하지 않는다.
 * 관리자 "알림 보냄" 오늘 집계에 쓴다. 확률·대답 원문은 담지 않는다(날짜·일수뿐).
 */
@Entity
@Table(name = "companion_risk_pattern_notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionRiskPatternNotice {

    @Id
    @Column(name = "ward_id", length = 6)
    private String wardId;

    @Column(name = "last_notified_date", nullable = false)
    private LocalDate lastNotifiedDate;

    @Column(nullable = false)
    private int days;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;
}
