package kr.silverbridge.main.domain.notification.entity;

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
 * notification_log의 결과 enum과 DB CHECK 제약 동기화 가드.
 *
 * <p>{@code UserStatusCheckSyncTest}와 같은 함정을 막는다 - enum에 값만 더하면 INSERT가 CHECK 위반으로 실패한다.
 * 이 테이블은 기록 실패를 삼키므로(발송 우선) 500이 아니라 <b>이력이 조용히 빠지는</b> 형태로 드러나 더 늦게 발견된다.</p>
 */
class NotificationLogCheckSyncTest {

    @Test
    @DisplayName("NotificationLogResult 전수가 chk_notification_log_result 허용 목록에 있다")
    void result_동기화() throws IOException {
        String allowed = latestCheck("chk_notification_log_result", "result");
        for (NotificationLogResult value : NotificationLogResult.values()) {
            assertThat(allowed).as("enum %s 이(가) CHECK에 없음 - CHECK 재정의 마이그레이션 필요", value)
                    .contains("'" + value.name() + "'");
        }
    }

    @Test
    @DisplayName("NotificationNotSentReason 전수가 chk_notification_log_not_sent_reason 허용 목록에 있다")
    void notSentReason_동기화() throws IOException {
        String allowed = latestCheck("chk_notification_log_not_sent_reason", "not_sent_reason");
        for (NotificationNotSentReason value : NotificationNotSentReason.values()) {
            assertThat(allowed).as("enum %s 이(가) CHECK에 없음 - CHECK 재정의 마이그레이션 필요", value)
                    .contains("'" + value.name() + "'");
        }
    }

    // 모든 V*.sql 중 해당 CHECK를 정의하는 가장 높은 버전의 허용 목록을 찾는다.
    private String latestCheck(String constraint, String column) throws IOException {
        Pattern definition = Pattern.compile(
                constraint + "\\s+CHECK\\s*\\(" + column + "\\s+IN\\s*\\(([^)]+)\\)", Pattern.DOTALL);
        List<Resource> sorted = Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*.sql"))
                .sorted(Comparator.comparingInt(this::versionOf))
                .toList();

        String latest = null;
        for (Resource migration : sorted) {
            Matcher matcher = definition.matcher(migration.getContentAsString(StandardCharsets.UTF_8));
            while (matcher.find()) {
                latest = matcher.group(1);
            }
        }
        assertThat(latest).as("%s CHECK 정의를 마이그레이션에서 찾지 못함", constraint).isNotNull();
        return latest;
    }

    private int versionOf(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
