package kr.silverbridge.main.domain.companion;

import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안부 말벗 enum과 CHECK 제약이 같은지 고정한다. enum에 값을 더하면 이 테스트가 깨진다 - CHECK를 새 마이그레이션으로
 * 재정의할 것(기존 마이그레이션 수정 금지, {@code UserStatusCheckSyncTest}와 같은 취지).
 *
 * <p>제약마다 그 제약을 마지막으로 정의한 마이그레이션을 찾아 대조한다(2026-11-12 영향 범위 점검 L-2 - 예전에는 V65·V72 파일을
 * 이름으로 고정해, 다음 재정의 때 시험 경로도 바꿔야 했다).</p>
 */
class CompanionCheckSyncTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    @Test
    @DisplayName("상태·종료 이유·기분·시간대 CHECK 값이 enum과 같다(제약마다 마지막 정의 기준)")
    void checksMatchEnums() {
        assertThat(latestCheckValues("chk_companion_session_status")).isEqualTo(names(CompanionSessionStatus.values()));
        assertThat(latestCheckValues("chk_companion_session_end_reason")).isEqualTo(names(CompanionEndReason.values()));
        assertThat(latestCheckValues("chk_companion_session_mood")).isEqualTo(names(CompanionMood.values()));
        Set<String> slots = Arrays.stream(CompanionSlot.values()).map(CompanionSlot::code).collect(Collectors.toSet());
        assertThat(latestCheckValues("chk_companion_session_slot")).isEqualTo(slots);
        assertThat(latestCheckValues("chk_companion_question_slot")).isEqualTo(slots);

        // V66 주의 알림 기록 - 걱정·우울만(CompanionMoodDays.isConcerning과 같은 두 값)
        assertThat(latestCheckValues("chk_companion_notice_mood"))
                .isEqualTo(Set.of(CompanionMood.WORRY.name(), CompanionMood.DEPRESSED.name()));
    }

    @Test
    @DisplayName("종료 이유 CHECK의 마지막 정의는 V72다(AVOIDED 추가) - 찾는 규칙이 재정의를 따라가는지 확인")
    void endReasonLatestDefinitionIsV72OrLater() {
        assertThat(version(latestDefinition("chk_companion_session_end_reason"))).isGreaterThanOrEqualTo(72);
    }

    private static Set<String> latestCheckValues(String constraint) {
        return checkValues(read(latestDefinition(constraint)), constraint);
    }

    /** 그 제약의 CHECK 정의(이름 + CHECK)가 들어 있는 마이그레이션 중 버전이 가장 높은 파일. */
    private static Path latestDefinition(String constraint) {
        Pattern definition = Pattern.compile(constraint + "\\s+CHECK\\s*\\(");
        List<Path> files;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            files = s.filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                    .filter(p -> definition.matcher(read(p)).find())
                    .sorted(Comparator.comparingInt(CompanionCheckSyncTest::version))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(files).as(constraint + " 정의").isNotEmpty();
        return files.get(files.size() - 1);
    }

    private static int version(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        assertThat(m.matches()).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
