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
 * AdminAuditAction enum과 DB CHECK 제약(chk_admin_audit_action)의 동기화 가드 (C-S3-1 재발 방지).
 * <p>
 * V1의 CHECK가 V14에서 추가된 DRAFT 액션을 허용하지 않아 공지 임시저장 기능 전체가
 * CHECK 위반(23514)으로 롤백·500이 났던 결함의 재발을 막는다. enum에 값을 추가하면
 * CHECK를 재정의하는 마이그레이션도 함께 추가해야 이 테스트가 통과한다.
 */
class AdminAuditActionCheckSyncTest {

    private static final Pattern CHECK_DEFINITION = Pattern.compile(
            "chk_admin_audit_action\\s+CHECK\\s*\\(action IN \\(([^)]+)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("AdminAuditAction enum 전수가 최신 마이그레이션의 CHECK 허용 목록에 포함된다")
    void enum_값은_최신_CHECK_허용목록과_동기화() throws IOException {
        String latestCheckBody = findLatestCheckDefinition();

        for (AdminAuditAction action : AdminAuditAction.values()) {
            assertThat(latestCheckBody)
                    .as("enum %s 이(가) chk_admin_audit_action CHECK에 없음 — CHECK 재정의 마이그레이션 필요", action)
                    .contains("'" + action.name() + "'");
        }
    }

    // 모든 V*.sql 중 chk_admin_audit_action CHECK를 정의하는 가장 높은 버전의 허용 목록을 찾는다.
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

        assertThat(latest).as("chk_admin_audit_action CHECK 정의를 마이그레이션에서 찾지 못함").isNotNull();
        return latest;
    }

    private int versionOf(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
