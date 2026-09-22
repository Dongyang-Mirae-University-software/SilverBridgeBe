package kr.silverbridge.main.migration;

import kr.silverbridge.main.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션 전체 적용 검증.
 *
 * <p>이 클래스의 컨텍스트가 뜬다는 것 자체가 두 가지를 뜻한다 - Flyway가 빈 DB에 V1부터 끝까지 실패 없이 적용됐고,
 * 모든 엔티티가 그 스키마와 맞는다({@code ddl-auto=validate}). 2026-09-21 이전에는 이 둘을 배포 서버 기동으로만 확인했다.</p>
 */
class MigrationIntegrationTest extends PostgresIntegrationTest {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("빈 DB에 모든 마이그레이션이 성공적으로 적용되고, 최신 버전이 소스의 마지막 V 파일과 같다")
    void 전체_마이그레이션_적용() throws IOException {
        Integer failed = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        Integer applied = jdbcTemplate.queryForObject(
                "select max(version::int) from flyway_schema_history where version is not null", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isEqualTo(latestSourceVersion());
    }

    /** src/main/resources/db/migration 의 가장 큰 V 번호 - 새 마이그레이션이 추가돼도 테스트를 고칠 필요가 없다. */
    private int latestSourceVersion() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            return files.map(path -> VERSION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElseThrow();
        }
    }
}
