package kr.silverbridge.main.domain.user;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인이 관리자 정지를 덮어쓰지 않는다 (ADMIN-G04).
 *
 * <p>로그인은 사용자를 읽고 BCrypt 검증(수백 ms) 뒤 last_login_at만 바꿔 커밋한다. 그 사이 관리자가 status를 바꿔 커밋하면,
 * 전체 컬럼 UPDATE에서는 로그인이 읽어 둔 옛 status(ACTIVE)로 덮여 정지가 풀렸다. 여기서는 엔티티를 읽은 뒤 같은 행을
 * SQL로 바꿔(=다른 트랜잭션의 커밋을 흉내) 로그인 쪽 flush가 그 컬럼을 건드리지 않는지 본다.</p>
 */
class UserDynamicUpdateIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("last_login_at만 바꾼 flush는 그 사이 바뀐 status·role·name을 덮지 않는다")
    void 로그인_flush가_관리자변경을_덮지않는다() {
        userRepository.saveAndFlush(TestData.user("DYN001", "원래이름", Role.GUARDIAN));
        entityManager.clear();

        User loggingIn = userRepository.findById("DYN001").orElseThrow();   // 로그인이 읽은 스냅샷(ACTIVE)

        jdbcTemplate.update("update users set status = 'RESTRICTED', status_reason = '테스트 정지', name = '바뀐이름' "
                + "where id = 'DYN001'");                                      // 관리자 정지 커밋

        loggingIn.updateLastLoginAt();
        entityManager.flush();                                                 // 로그인 커밋

        assertThat(jdbcTemplate.queryForObject("select status from users where id = 'DYN001'", String.class))
                .isEqualTo("RESTRICTED");
        assertThat(jdbcTemplate.queryForObject("select name from users where id = 'DYN001'", String.class))
                .isEqualTo("바뀐이름");
        assertThat(jdbcTemplate.queryForObject("select last_login_at is not null from users where id = 'DYN001'",
                Boolean.class)).isTrue();
    }
}
