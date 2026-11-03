package kr.silverbridge.main.domain.companion.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 탈퇴한 피보호자의 AI 쪽 말벗 데이터 삭제 대기열(V65, 결정 C). AI 삭제에 성공하면 행을 지운다.
 *
 * <p>회원 행에 FK를 두지 않는다 - 회원이 hard delete된 뒤에도 남아 있어야 재시도할 수 있다. 담는 것은 ID뿐이다.
 * 탈퇴 트랜잭션 안(BEFORE_COMMIT)에서 넣으므로 스윕 purge(리스너를 거치지 않음)로 지워진 회원도 빠지지 않는다.</p>
 */
@Entity
@Table(name = "companion_purge_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanionPurgeRequest {

    @Id
    @Column(name = "ward_id", length = 6)
    private String wardId;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    private CompanionPurgeRequest(String wardId, OffsetDateTime requestedAt) {
        this.wardId = wardId;
        this.requestedAt = requestedAt;
    }

    public static CompanionPurgeRequest of(String wardId, OffsetDateTime requestedAt) {
        return new CompanionPurgeRequest(wardId, requestedAt);
    }

    public void recordFailure(OffsetDateTime now) {
        this.attempts++;
        this.lastAttemptAt = now;
    }
}
