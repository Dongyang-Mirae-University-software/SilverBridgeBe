package kr.silverbridge.main.domain.checkin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 안부 말벗은 어르신의 말(받아쓴 글자)·말벗 답·요약·보호자 질문을 로그에 남기지 않는다(2026-11-01). 로그 문 인자에
 * 대화 내용·음성·사진·API 키가 들어가면 실패시킨다. 허용: 사용자 ID·세션 ID·턴 번호·개수·상태 코드·고정 오류 코드·예외 클래스명.
 */
class CheckinLogGuardTest {

    private static final Pattern LOG_STATEMENT = Pattern.compile("log\\.(trace|debug|info|warn|error)\\([^;]*;", Pattern.DOTALL);
    private static final List<String> FORBIDDEN = List.of(
            "transcript", "reply()", "summary", "question", "audioBytes", "frameBytes)", "body", "data",
            "getApiKey", "apiKey", "readTree", "toString()", "getMessage()", "evidence", "selfReport");

    @Test
    void checkin_package_never_logs_conversation_content_or_key() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/kr/silverbridge/main/domain/checkin"))) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                var matcher = LOG_STATEMENT.matcher(Files.readString(file));
                while (matcher.find()) {
                    // 로그 문구(따옴표 안)는 빼고 인자만 본다
                    String args = matcher.group().replaceAll("\"[^\"]*\"", "\"\"");
                    for (String word : FORBIDDEN) {
                        assertThat(args).as(file + " 의 로그 인자에 " + word).doesNotContain(word);
                    }
                }
            }
        }
    }
}
