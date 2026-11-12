package kr.silverbridge.main.domain.companion;

import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안부 말벗 enum과 V65 CHECK 제약이 같은지 고정한다. enum에 값을 더하면 이 테스트가 깨진다 - CHECK를 새 마이그레이션으로
 * 재정의할 것(기존 V65 수정 금지, {@code UserStatusCheckSyncTest}와 같은 취지).
 */
class CompanionCheckSyncTest {

    private static final Path V65 = Path.of("src/main/resources/db/migration/V65__add_companion.sql");
    // 종료 이유 CHECK는 V72에서 AVOIDED를 더해 재정의했다(2026-11-12) - 마지막으로 정의한 파일을 본다
    private static final Path V72 = Path.of("src/main/resources/db/migration/V72__add_companion_end_reason_avoided.sql");

    @Test
    @DisplayName("상태·종료 이유·기분·시간대 CHECK 값이 enum과 같다")
    void checksMatchEnums() throws IOException {
        String sql = Files.readString(V65, StandardCharsets.UTF_8);

        assertThat(checkValues(sql, "chk_companion_session_status")).isEqualTo(names(CompanionSessionStatus.values()));
        assertThat(checkValues(Files.readString(V72, StandardCharsets.UTF_8), "chk_companion_session_end_reason"))
                .isEqualTo(names(CompanionEndReason.values()));
        assertThat(checkValues(sql, "chk_companion_session_mood")).isEqualTo(names(CompanionMood.values()));
        Set<String> slots = Arrays.stream(CompanionSlot.values()).map(CompanionSlot::code).collect(Collectors.toSet());
        assertThat(checkValues(sql, "chk_companion_session_slot")).isEqualTo(slots);
        assertThat(checkValues(sql, "chk_companion_question_slot")).isEqualTo(slots);

        // V66 주의 알림 기록 - 걱정·우울만(CompanionMoodDays.isConcerning과 같은 두 값)
        String v66 = Files.readString(Path.of("src/main/resources/db/migration/V66__add_companion_notice.sql"),
                StandardCharsets.UTF_8);
        assertThat(checkValues(v66, "chk_companion_notice_mood"))
                .isEqualTo(Set.of(CompanionMood.WORRY.name(), CompanionMood.DEPRESSED.name()));
    }

    private static Set<String> checkValues(String sql, String constraint) {
        Matcher m = Pattern.compile(constraint + "\\s+CHECK\\s*\\(\\s*\\w+\\s+IN\\s*\\(([^)]*)\\)").matcher(sql);
        assertThat(m.find()).as(constraint).isTrue();
        return Arrays.stream(m.group(1).split(","))
                .map(v -> v.trim().replace("'", ""))
                .collect(Collectors.toSet());
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
