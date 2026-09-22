package kr.silverbridge.main.migration;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Java enum의 모든 값이 DB CHECK 제약을 통과하는지 실제 insert·update로 확인한다.
 *
 * <p>enum에 값만 더하고 CHECK 재정의 마이그레이션을 빠뜨리면 그 값을 쓰는 순간 23514로 실패하고 본 작업까지 롤백된다
 * (C-S3-1·V46·V47이 막으려던 함정). 단위 테스트({@code *CheckSyncTest})는 마이그레이션 SQL 텍스트를 대조하는데,
 * 이 테스트는 적용된 스키마에 실제로 넣어 본다 - 텍스트 파싱이 놓치는 경우(따옴표·다음 마이그레이션의 재정의)까지 잡는다.</p>
 */
class EnumCheckConstraintIntegrationTest extends PostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("users.status - Status enum 전 값이 chk_users_status를 통과한다")
    void 회원_상태() {
        userRepository.saveAndFlush(TestData.user("WD0001", "김영희", Role.WARD));

        for (Status status : Status.values()) {
            assertThatNoException().as("Status.%s", status).isThrownBy(() ->
                    jdbcTemplate.update("update users set status = ? where id = ?", status.name(), "WD0001"));
        }
    }

    @Test
    @DisplayName("admin_audit_log.action - AdminAuditAction enum 전 값이 chk_admin_audit_action을 통과한다")
    void 감사_로그_행위() {
        Arrays.stream(AdminAuditAction.values()).forEach(action ->
                assertThatNoException().as("AdminAuditAction.%s", action).isThrownBy(() ->
                        jdbcTemplate.update("insert into admin_audit_log (admin_id, action) values (?, ?)",
                                "AD0001", action.name())));
    }

    @Test
    @DisplayName("anomaly_incident.review_status - AnomalyReviewStatus enum 전 값이 CHECK를 통과한다")
    void 이상감지_판정_상태() {
        userRepository.saveAndFlush(TestData.user("WD0001", "김영희", Role.WARD));
        jdbcTemplate.update("""
                insert into anomaly_incident (ward_id, session_id, detected_type, started_at, last_detected_at,
                                              event_count, max_confidence, review_status, created_at, updated_at)
                values ('WD0001', 'sess-living', 'FIRE', now(), now(), 1, 0.8, 'PENDING', now(), now())
                """);

        for (AnomalyReviewStatus status : AnomalyReviewStatus.values()) {
            assertThatNoException().as("AnomalyReviewStatus.%s", status).isThrownBy(() ->
                    jdbcTemplate.update("update anomaly_incident set review_status = ? where ward_id = ?",
                            status.name(), "WD0001"));
        }
    }
}
