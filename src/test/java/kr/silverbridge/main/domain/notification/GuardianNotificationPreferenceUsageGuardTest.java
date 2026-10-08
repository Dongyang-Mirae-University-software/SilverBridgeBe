package kr.silverbridge.main.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정책 고정 - 보호자 알림 종류 설정은 <b>필수 알림(SOS·이상감지 발생)의 푸시 경로를 줄이지 못한다</b>.
 *
 * <p>이 설정을 읽는 곳은 설정 API 자신과 미복용 요약 플래너뿐이어야 한다. 디스패처·알림 리스너·SOS·이상감지
 * 발송 코드가 읽기 시작하면 강제 채널이 설정으로 줄어들 수 있다(디스패처 제외 채널 파라미터는 거부안).</p>
 */
class GuardianNotificationPreferenceUsageGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> ALLOWED = List.of(
            "GuardianNotificationPreferenceService.java",
            "GuardianNotificationTypeSettingController.java",
            "MedicationMissedAlertPlanner.java");

    @Test
    @DisplayName("종류 설정(서비스·저장소·엔티티)은 설정 API와 미복용 요약 플래너만 참조한다")
    void 참조처_고정() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> users = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("GuardianNotificationPreference")
                            || p.getFileName().toString().equals("GuardianNotificationPreferenceService.java"))
                    .filter(p -> read(p).contains("GuardianNotificationPreference"))
                    .map(p -> p.getFileName().toString())
                    .toList();

            assertThat(users).allMatch(ALLOWED::contains);
            assertThat(users).contains("MedicationMissedAlertPlanner.java");
        }
    }

    @Test
    @DisplayName("디스패처는 종류 설정을 모른다 - 강제 채널은 이 설정으로 줄지 않는다")
    void 디스패처_미참조() throws IOException {
        String dispatcher = Files.readString(
                MAIN.resolve("kr/silverbridge/main/domain/notification/dispatch/NotificationDispatcher.java"));
        assertThat(dispatcher).doesNotContain("GuardianNotificationPreference");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
