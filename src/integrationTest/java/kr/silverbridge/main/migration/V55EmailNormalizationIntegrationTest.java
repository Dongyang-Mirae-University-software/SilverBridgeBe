package kr.silverbridge.main.migration;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * V55(이메일 소문자 통일·이름 앞뒤 공백 정리) 검증 (AUTH-G09·AUTH-G13).
 *
 * <p>빈 DB에는 V55가 이미 적용돼 있으므로, 대소문자 무시 인덱스를 테스트 트랜잭션 안에서 지우고 "V55 이전" 데이터를 만든 뒤
 * V55 스크립트를 다시 실행한다. PostgreSQL DDL은 트랜잭션 안에서 롤백되므로 다른 테스트에 영향이 없다.
 * 실패를 검증하는 테스트는 PostgreSQL 트랜잭션이 중단 상태가 되므로 그 검증이 마지막 동작이다.</p>
 */
class V55EmailNormalizationIntegrationTest extends PostgresIntegrationTest {

    private static final Path V55 = Path.of("src/main/resources/db/migration/V55__normalize_user_email_lowercase.sql");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("적용 후 - 대소문자만 다른 이메일로 두 번째 계정을 저장하면 uq_users_email_lower가 막는다")
    void 대소문자무시_유일성() {
        userRepository.saveAndFlush(user("V55A01", "case@test.local", "가나다"));

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                userRepository.saveAndFlush(user("V55A02", "Case@Test.local", "라마바")));
    }

    @Test
    @DisplayName("대소문자만 다른 중복이 있으면 데이터를 바꾸기 전에 명확한 메시지로 중단한다")
    void 중복있으면_중단() throws IOException {
        jdbcTemplate.execute("DROP INDEX uq_users_email_lower");
        userRepository.saveAndFlush(user("V55B01", "dup@test.local", "가나다"));
        userRepository.saveAndFlush(user("V55B02", "DUP@test.local", "라마바"));

        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> jdbcTemplate.execute(Files.readString(V55)))
                .withMessageContaining("대소문자만 다른 중복 이메일이 있어 마이그레이션을 중단합니다")
                .withMessageContaining("V55B01,V55B02");
    }

    @Test
    @DisplayName("중복이 없으면 이메일을 소문자로, 이름 앞뒤 공백을 정리하고 인덱스를 만든다")
    void 중복없으면_정규화() throws IOException {
        jdbcTemplate.execute("DROP INDEX uq_users_email_lower");
        userRepository.saveAndFlush(user("V55C01", "Mixed.Case@Test.local", " 홍길동 "));
        userRepository.saveAndFlush(user("V55C02", "lower@test.local", "김철수"));

        jdbcTemplate.execute(Files.readString(V55));

        assertThat(jdbcTemplate.queryForObject("select email from users where id = 'V55C01'", String.class))
                .isEqualTo("mixed.case@test.local");
        assertThat(jdbcTemplate.queryForObject("select name from users where id = 'V55C01'", String.class))
                .isEqualTo("홍길동");
        assertThat(jdbcTemplate.queryForObject("select email from users where id = 'V55C02'", String.class))
                .isEqualTo("lower@test.local");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uq_users_email_lower'", Integer.class))
                .isEqualTo(1);
    }

    private static User user(String id, String email, String name) {
        return User.builder()
                .id(id)
                .email(email)
                .name(name)
                .role(Role.GUARDIAN)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .address("서울시 테스트구")
                .addressDetail("101호")
                .build();
    }
}
