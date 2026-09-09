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
 * Status enum과 DB CHECK 제약(chk_users_status)의 동기화 가드.
 *
 * <p>{@code AdminAuditActionCheckSyncTest}와 같은 함정을 막는다 - users.status에 CHECK가 걸려 있어
 * enum에 값만 더하면 UPDATE가 CHECK 위반(23514)으로 실패하고, 같은 트랜잭션의 본 작업까지 롤백돼 500이 난다.
 * V4가 허용 목록을 ACTIVE·INACTIVE로 좁혀 놓은 뒤 V47에서 RESTRICTED를 더하면서 실제로 필요해진 검사다.</p>
 */
class UserStatusCheckSyncTest {

    private static final Pattern CHECK_DEFINITION = Pattern.compile(
            "chk_users_status\\s+CHECK\\s*\\(status\\s+IN\\s*\\(([^)]+)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("Status enum 전수가 최신 마이그레이션의 CHECK 허용 목록에 포함된다")
    void enum_값은_최신_CHECK_허용목록과_동기화() throws IOException {
        String latestCheckBody = findLatestCheckDefinition();

        for (Status status : Status.values()) {
            assertThat(latestCheckBody)
                    .as("enum %s 이(가) chk_users_status CHECK에 없음 - CHECK 재정의 마이그레이션 필요", status)
                    .contains("'" + status.name() + "'");
        }
    }

    // 모든 V*.sql 중 chk_users_status CHECK를 정의하는 가장 높은 버전의 허용 목록을 찾는다.
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

        assertThat(latest).as("chk_users_status CHECK 정의를 마이그레이션에서 찾지 못함").isNotNull();
        return latest;
    }

    private int versionOf(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
