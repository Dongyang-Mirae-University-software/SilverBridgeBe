package kr.silverbridge.main.domain.camera.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 이상감지 카메라. 소유자 = 피보호자({@code wardId}, 6자리). 보호자는 connections(ACTIVE)로 전이 접근한다.
 *
 * <p>식별자 이원화(요구사항):
 * <ul>
 *   <li>{@code sessionId} — 카메라 고유 SessionID. 백엔드 발급, AI 서버 sessionId·보호자 노출 키.</li>
 *   <li>{@code deviceId}  — 백엔드 발급 기기 토큰(FE가 localStorage에 영속). 재등록 dedup 키. 하드웨어 지문 아님.</li>
 * </ul>
 * {@code label}은 설치 위치(방 이름 — 거실/안방/방1~3 등). 소유권·인가는 문자열 파싱이 아니라 DB 행으로 판정한다.
 * FK 대신 {@code String wardId}로만 저장(프로젝트 관례 — connections/inquiries 동일). createdAt/updatedAt은 {@link BaseTimeEntity}.</p>
 */
@Entity
@Table(name = "camera",
        indexes = {
                @Index(name = "idx_cameras_ward", columnList = "ward_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cameras_session", columnNames = "session_id"),
                @UniqueConstraint(name = "uq_cameras_ward_device", columnNames = {"ward_id", "device_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Camera extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소유 피보호자 (6자리)
    @Column(name = "ward_id", nullable = false, length = 6)
    private String wardId;

    // 카메라 고유 SessionID (백엔드 발급, AI sessionId)
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    // 백엔드 발급 기기 토큰 (FE localStorage 영속, 재등록 dedup)
    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    // 설치 위치(방 이름) — 거실/안방/방1/방2/방3 등
    @Column(nullable = false, length = 30)
    private String label;

    // 등록 주체 (피보호자 본인 또는 보호자). 등록자 삭제 시 SET NULL.
    @Column(name = "registered_by", length = 6)
    private String registeredBy;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    // 방 이름 변경
    public void rename(String label) {
        this.label = label;
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }
}
