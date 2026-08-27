package kr.silverbridge.main.domain.sos.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 피보호자 SOS 발생 이력. 피보호자가 SOS 화면에서 긴급 SOS를 누르거나 보호자에게 직접 전화할 때마다
 * 한 행이 기록된다.
 *
 * <p>이력은 알림 발송과 무관하게 항상 남는다(SOS 처리의 진실원본). 발송된 보호자 목록은 저장하지 않으며,
 * 알림 대상은 발송 시점의 ACTIVE connections에서 도출된다. {@code wardId}는 탈퇴(hard delete) 시
 * {@code ON DELETE SET NULL}로 익명 보존된다(access_logs와 동일 정책).</p>
 *
 * <p><b>이력이 답하는 것은 "언제·어떤 경로로 발생했는가"까지다</b>(2026-08-26 결정, V39). 보호자가 처리
 * 결과를 남기는 ACK 기능(V33)은 철회했다 - 보호자 화면이 붙지 않아 전건이 미처리로 쌓이는 지표가 됐고,
 * 서버는 "체크 누락"과 "실제 미처리"를 구분할 수 없었다. 처리 결과를 다시 도입하려면 보호자 화면 연동이
 * 선행되어야 한다.</p>
 */
@Entity
@Table(name = "sos_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SosEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ward_id", length = 6)
    private String wardId;

    /**
     * 발생 위치 자유 문구(선택). 프론트가 보낸 값을 그대로 보관하며 서버는 위치를 추정하지 않는다.
     * {@code null}이면 위치 미상 - 표시·보관 전용이라 알림 발송에는 관여하지 않는다.
     */
    @Column(name = "location", length = 100)
    private String location;

    /**
     * 발생 경로. 표시 전용이며 알림 발송 조건에 개입하지 않는다 - 두 경로 모두 보호자 알림이 동일하게 나간다.
     * 값을 주지 않으면 {@link SosTriggerType#SOS_BUTTON}으로 기록한다(기존 호출 방식 하위호환).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private SosTriggerType triggerType;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    public SosEvent(String wardId, String location, SosTriggerType triggerType) {
        this.wardId = wardId;
        this.location = location;
        this.triggerType = (triggerType == null) ? SosTriggerType.SOS_BUTTON : triggerType;
    }
}
