package kr.silverbridge.main.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 예외 원문 로그 금지 가드 (알림 이력 불변 규칙 ②, 2026-10-06 점검).
 *
 * <p>예외 메시지에는 입력 값·이메일·전화번호·기기 토큰·payload 가 섞일 수 있다. 아래 파일들은 점검에서 원문 로그를
 * 걷어낸 곳이라, 다시 {@code log.xxx(... e.getMessage() ...)} 형태로 돌아가면 실패시킨다. 새 파일을 같은 규칙에
 * 맞췄다면 목록에 더한다.</p>
 */
class LogRawExceptionGuardTest {

    private static final String BASE = "src/main/java/kr/silverbridge/main/";

    /** 로그 문 하나 안(세미콜론 전까지)에 getMessage() 가 들어간 경우. */
    private static final Pattern LOG_WITH_MESSAGE =
            Pattern.compile("log\\.(trace|debug|info|warn|error)\\([^;]*getMessage\\(\\)", Pattern.DOTALL);

    private static final List<String> GUARDED = List.of(
            "global/client/FileServerClient.java",
            "global/client/SmsSender.java",
            "global/websocket/WebSocketEventPublisher.java",
            "domain/auth/service/PasswordResetService.java",
            "domain/auth/oauth/KakaoOAuthClient.java",
            "domain/notification/service/AlimtalkSender.java",
            "domain/notification/service/FcmService.java",
            "domain/notification/dispatch/NotificationDispatcher.java",
            "domain/sos/service/SosNotificationCooldown.java",
            "domain/sos/listener/SosNotificationListener.java",
            "domain/anomaly/service/AnomalyNotificationCooldown.java",
            "domain/anomaly/listener/AnomalyNotificationListener.java",
            "domain/chat/client/AiChatClient.java",
            "domain/chat/service/ChatRelayService.java",
            "domain/chat/listener/ChatLogPurgeListener.java");

    @Test
    void guarded_files_do_not_log_exception_messages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String file : GUARDED) {
            String source = Files.readString(Path.of(BASE + file));
            if (LOG_WITH_MESSAGE.matcher(source).find()) {
                violations.add(file);
            }
        }
        assertThat(violations)
                .as("예외 메시지(getMessage)를 로그에 남기는 파일 - 클래스명·고정 코드만 남길 것")
                .isEmpty();
    }

    @Test
    void dispatcher_does_not_log_exception_object_with_stack() throws IOException {
        String source = Files.readString(Path.of(BASE + "domain/notification/dispatch/NotificationDispatcher.java"));
        // 채널 예외 객체를 통째로 넘기면 스택과 메시지(토큰·번호)가 로그에 남는다.
        assertThat(source).doesNotContain("channelType, e)");
    }

    @Test
    void global_exception_handler_does_not_log_user_supplied_values() throws IOException {
        String source = Files.readString(Path.of(BASE + "global/exception/GlobalExceptionHandler.java"));
        // 요청 바디·파라미터 값이 섞이는 세 예외는 메시지를 로그에 남기지 않는다.
        assertThat(source)
                .doesNotContain("HttpMessageNotReadableException: {}\", e.getMessage()")
                .doesNotContain("MethodArgumentTypeMismatchException: {}\", e.getMessage()")
                .doesNotContain("getMostSpecificCause().getMessage()");
    }
}
