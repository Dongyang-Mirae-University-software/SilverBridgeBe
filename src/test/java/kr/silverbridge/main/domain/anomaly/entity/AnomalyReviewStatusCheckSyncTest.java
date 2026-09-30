package kr.silverbridge.main.domain.anomaly.entity;

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
 * AnomalyReviewStatus enum과 DB CHECK 제약(chk_anomaly_incident_status)의 동기화 가드.
 *
 * <p>{@code UserStatusCheckSyncTest}·{@code AdminAuditActionCheckSyncTest}와 같은 함정을 막는다 -
 * {@code anomaly_incident.review_status}에 CHECK가 걸려 있어 enum에 값만 더하면 UPDATE가 CHECK
 * 위반(23514)으로 실패한다. 테이블은 V42에서 만들어지고 V43에서 드롭된 뒤 V44가 같은 제약명으로
 * 다시 만들었으므로(버전 순으로는 V44가 최신), 드롭·재생성을 거치더라도 "가장 높은 버전의 정의"를
 * 찾는 이 방식이 여전히 맞는 정의를 고른다.</p>
 */
class AnomalyReviewStatusCheckSyncTest {

    private static final Pattern CHECK_DEFINITION = Pattern.compile(
            "chk_anomaly_incident_status\\s+CHECK\\s*\\(review_status\\s+IN\\s*\\(([^)]+)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("AnomalyReviewStatus enum 전수가 최신 마이그레이션의 CHECK 허용 목록에 포함된다")
    void enum_값은_최신_CHECK_허용목록과_동기화() throws IOException {
        String latestCheckBody = findLatestCheckDefinition();

        for (AnomalyReviewStatus status : AnomalyReviewStatus.values()) {
            assertThat(latestCheckBody)
                    .as("enum %s 이(가) chk_anomaly_incident_status CHECK에 없음 - CHECK 재정의 마이그레이션 필요", status)
                    .contains("'" + status.name() + "'");
        }
    }

    // 모든 V*.sql 중 chk_anomaly_incident_status CHECK를 정의하는 가장 높은 버전의 허용 목록을 찾는다.
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

        assertThat(latest).as("chk_anomaly_incident_status CHECK 정의를 마이그레이션에서 찾지 못함").isNotNull();
        return latest;
    }

    private int versionOf(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
