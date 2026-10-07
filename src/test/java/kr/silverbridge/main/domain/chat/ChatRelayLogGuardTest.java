package kr.silverbridge.main.domain.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 챗 중계는 상담 내용(민감 정보)을 로그에 남기지 않는다(2026-10-07). 로그 문 안에 메시지·응답 본문·기록 내용·API 키가
 * 들어가면 실패시킨다. 로그에는 userId·길이·상태 코드·예외 클래스명까지만 허용한다.
 */
class ChatRelayLogGuardTest {

    private static final Pattern LOG_STATEMENT = Pattern.compile("log\\.(trace|debug|info|warn|error)\\([^;]*;", Pattern.DOTALL);
    private static final List<String> FORBIDDEN = List.of(
            "message()", "history()", "context()", "uiSelection()", "body", "reply", "getApiKey", "apiKey",
            "readTree", "toString()", "getMessage()");

    @Test
    void chat_package_never_logs_conversation_content_or_key() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/kr/silverbridge/main/domain/chat"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = LOG_STATEMENT.matcher(Files.readString(file));
                while (matcher.find()) {
                    String statement = matcher.group();
                    // 로그 문구(따옴표 안)는 제외하고 인자만 본다 - "AI 응답에 data 없음" 같은 고정 문구는 허용
                    String args = statement.replaceAll("\"[^\"]*\"", "\"\"");
                    for (String word : FORBIDDEN) {
                        assertThat(args).as(file + " 의 로그 인자에 " + word).doesNotContain(word);
                    }
                }
            }
        }
    }
}
