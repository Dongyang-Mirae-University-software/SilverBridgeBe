package kr.silverbridge.main.domain.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대화 원문(말벗 문장·어르신 대답·받아 적은 글)은 동의 시에만 DB에 저장하고 로그에는 절대 남기지 않는다(2026-11-11).
 * 말벗 도메인 소스의 로그 호출 인자에 원문을 담는 변수·getter가 들어가지 않는지 본다(문자열 리터럴은 제외).
 */
class CompanionTranscriptLogGuardTest {

    private static final Path BASE = Path.of("src/main/java/kr/silverbridge/main/domain/companion");
    private static final Pattern LOG_CALL = Pattern.compile("log\\.(trace|debug|info|warn|error)\\((.*?)\\);",
            Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"");
    private static final Pattern TEXT_IDENT = Pattern.compile(
            "\\b(answer|answerText|botText|transcript|reply|text|content|getBotText|getAnswerText|replyNode|transcriptNode)\\b");

    @Test
    @DisplayName("말벗 도메인 로그 호출에 원문 변수(answer·reply·transcript·botText·answerText·content)가 없다")
    void noTranscriptInLogs() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BASE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher call = LOG_CALL.matcher(Files.readString(file));
                while (call.find()) {
                    String args = STRING_LITERAL.matcher(call.group(2)).replaceAll("\"\"");
                    if (TEXT_IDENT.matcher(args).find()) {
                        violations.add(BASE.relativize(file) + ": " + args.strip());
                    }
                }
            }
        }
        assertThat(violations).as("로그에 대화 원문을 넘기지 말 것").isEmpty();
    }
}
