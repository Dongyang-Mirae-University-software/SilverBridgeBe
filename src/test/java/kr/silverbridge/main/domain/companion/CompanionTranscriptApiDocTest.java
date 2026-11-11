package kr.silverbridge.main.domain.companion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 말벗 저장 고지 문구 고정(2026-11-12 영향 범위 점검 M-1). 대답 글·받아 적은 글·말벗 응답 문장은 동의 시 저장되므로
 * 공개 Swagger·API 설명·DTO에 "저장하지 않는다"고 적으면 동의 고지와 어긋난다. 음성·사진은 여전히 저장하지 않는다.
 */
class CompanionTranscriptApiDocTest {

    private static final Path MAIN = Path.of("src/main/java/kr/silverbridge/main");
    /** 같은 문장 안에서 대화 글을 주어로 "저장하지 않"을 말하는가 - 문장 경계(. 。 " 줄바꿈 표시)를 넘지 않는다. */
    private static final Pattern STALE = Pattern.compile(
            "(대답|응답 문장|받아 적은|transcript|원문)[^.。\"\\\\]*저장하지 않");

    @Test
    @DisplayName("말벗 API 문서·DTO·Swagger에 '대답·응답 문장·받아 적은 글은 저장하지 않는다' 문구가 없다")
    void noStaleNoStorageStatement() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN.resolve("domain/companion"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        files.add(MAIN.resolve("global/config/SwaggerConfig.java"));
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (STALE.matcher(lines.get(i)).find()) {
                    violations.add(MAIN.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertThat(violations).as("동의 시 저장으로 바뀐 고지와 어긋나는 문구").isEmpty();
    }

    @Test
    @DisplayName("Swagger 말벗 태그는 동의 시 저장·30일 삭제·철회 즉시 삭제를 알린다")
    void swaggerTagStatesConsentRule() throws IOException {
        String swagger = Files.readString(MAIN.resolve("global/config/SwaggerConfig.java"));

        assertThat(swagger).contains("대화 내용 저장에 동의한 경우에만", "30일 뒤 지웁니다", "철회 시 즉시 삭제");
    }
}
