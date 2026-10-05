package kr.silverbridge.main.domain.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문자 폴백 상한({@link SmsFallbackLimiter})은 <b>디스패처의 필수 알림 폴백 경로에서만</b> 쓴다는 구조 고정.
 *
 * <p>다른 곳(예: 설정 기반 문자, 리스너)에서 부르기 시작하면 "SOS 문자 폴백에만 건다"는 불변식이 조용히 깨지고,
 * 복약·이상감지 같은 문자가 같은 한도를 나눠 쓰게 된다. 이상감지 클립 쿨다운의 "한 곳에서만 쓴다" 구조 테스트와
 * 같은 취지다(2026-10-05 영향 범위 점검 후속).</p>
 */
class SmsFallbackLimiterUsageTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path LIMITER = MAIN_SOURCES.resolve(
            "kr/silverbridge/main/domain/notification/dispatch/SmsFallbackLimiter.java");
    private static final Path DISPATCHER = MAIN_SOURCES.resolve(
            "kr/silverbridge/main/domain/notification/dispatch/NotificationDispatcher.java");

    @Test
    @DisplayName("SmsFallbackLimiter를 참조하는 운영 코드는 자기 자신과 NotificationDispatcher뿐이다")
    void 디스패처만_사용한다() throws IOException {
        List<Path> users;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            users = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(LIMITER))
                    .filter(p -> read(p).contains("SmsFallbackLimiter"))
                    .toList();
        }

        assertThat(users).containsExactly(DISPATCHER);
    }

    @Test
    @DisplayName("디스패처 안에서도 필수 알림 폴백 메서드 한 곳에서만 상한을 센다")
    void 필수알림_폴백에서만_센다() {
        String source = read(DISPATCHER);

        assertThat(source.split("smsFallbackLimiter\\.tryAcquire", -1).length - 1)
                .as("tryAcquire 호출 수").isEqualTo(1);
        int callIndex = source.indexOf("smsFallbackLimiter.tryAcquire");
        int methodStart = source.indexOf("private Outcome dispatchMandatory(");
        int nextMethodStart = source.indexOf("private static void attempt(");
        assertThat(callIndex).isBetween(methodStart, nextMethodStart);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }
}
