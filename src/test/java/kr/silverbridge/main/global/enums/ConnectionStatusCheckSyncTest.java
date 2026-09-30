package kr.silverbridge.main.global.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConnectionStatus enum과 DB CHECK 제약(chk_connections_status)의 동기화 가드.
 *
 * <p>{@code UserStatusCheckSyncTest}·{@code AdminAuditActionCheckSyncTest}와 같은 함정을 막는다 -
 * {@code connection} 테이블(V31에서 {@code connections}를 RENAME, 제약명은 그대로)의 status에 CHECK가
 * 걸려 있어 enum에 값만 더하면 UPDATE가 CHECK 위반(23514)으로 실패한다. V1의 CHECK를 V23이
 * (PENDING·ACTIVE·CANCELLED)에서 REFUSED·DISCONNECTED까지 넓게 재정의했다.</p>
 */
class ConnectionStatusCheckSyncTest {

    private static final Pattern CHECK_DEFINITION = Pattern.compile(
            "chk_connections_status\\s+CHECK\\s*\\(status\\s+IN\\s*\\(([^)]+)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("ConnectionStatus enum 전수가 최신 마이그레이션의 CHECK 허용 목록에 포함된다")
    void enum_값은_최신_CHECK_허용목록과_동기화() throws IOException {
        String latestCheckBody = findLatestCheckDefinition();

        for (ConnectionStatus status : ConnectionStatus.values()) {
            assertThat(latestCheckBody)
                    .as("enum %s 이(가) chk_connections_status CHECK에 없음 - CHECK 재정의 마이그레이션 필요", status)
                    .contains("'" + status.name() + "'");
        }
    }

    // 모든 V*.sql 중 chk_connections_status CHECK를 정의하는 가장 높은 버전의 허용 목록을 찾는다.
    private String findLatestCheckDefinition() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");

        List<Resource> sorted = Arrays.stream(migrations)
                .sorted(Comparator.comparingInt(this::versionOf))
                .toList();

        String latest = null;
        for (Resource migration : sorted) {
            String sql = migration.getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = CHECK_DEFINITION.matcher(sql);
            String lastInFile = null;
            while (matcher.find()) {
                lastInFile = matcher.group(1);
            }
            if (lastInFile != null) {
                latest = lastInFile;
            }
        }

        assertThat(latest).as("chk_connections_status CHECK 정의를 마이그레이션에서 찾지 못함").isNotNull();
        return latest;
    }

    private int versionOf(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
