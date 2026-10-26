package kr.silverbridge.main.global.response;

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
 * 정책 고정 - 사용자가 보낸 page로 {@code Pageable}을 만들 때는 반드시 보정을 거친다(2026-10-26 영향 범위 점검 L-2).
 *
 * <p>offset(page × size)이 int를 넘으면 Spring Data가 예외를 던져 500이었다. 지금 사용처는 모두
 * {@code AdminPaging.of} 또는 {@code PageOffsets.clampPage}를 거치지만, 새 목록이 {@code PageRequest.of(page, size)}를
 * 직접 만들면 같은 500이 돌아온다. 그래서 {@code PageRequest.of(}의 첫 인자는 숫자 0이거나
 * {@code PageOffsets.clampPage(}여야 하고, 페이지를 바꾸는 다른 생성 경로({@code withPage}·{@code ofSize})는 쓰지 않는다.</p>
 */
class PageRequestClampGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** 보정을 정의하는 곳 - 여기서는 보정된 값으로 PageRequest를 만든다 */
    private static final String EXEMPT = "AdminPaging.java";

    private static final Pattern PAGE_REQUEST_OF = Pattern.compile("PageRequest\\.of\\(\\s*([^,)]*)");
    private static final Pattern OTHER_PAGE_SETTERS = Pattern.compile("\\.withPage\\(|PageRequest\\.ofSize\\(|Pageable\\.ofSize\\(");

    @Test
    @DisplayName("PageRequest.of의 첫 인자는 0 또는 PageOffsets.clampPage(...)뿐이다 - 아니면 AdminPaging.of를 쓸 것")
    void 페이지_생성은_보정을_거친다() throws IOException {
        List<String> violations = new ArrayList<>();
        int clamped = 0;
        int fixedZero = 0;

        for (Path file : javaFiles()) {
            if (file.getFileName().toString().equals(EXEMPT)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher m = PAGE_REQUEST_OF.matcher(line);
                while (m.find()) {
                    String first = m.group(1).trim();
                    if (first.equals("0")) {
                        fixedZero++;
                    } else if (first.startsWith("PageOffsets.clampPage(")) {
                        clamped++;
                    } else {
                        violations.add(where(file, i) + " PageRequest.of(" + first + ", ...)");
                    }
                }
                if (OTHER_PAGE_SETTERS.matcher(line).find()) {
                    violations.add(where(file, i) + " " + line.trim());
                }
            }
        }

        assertThat(violations)
                .as("사용자 page로 Pageable을 만들 때는 AdminPaging.of 또는 PageOffsets.clampPage를 쓸 것"
                        + " (offset이 int를 넘으면 500) - 위반 위치")
                .isEmpty();
        // 스캔이 실제로 사용처를 찾았는지 - 경로가 틀려 아무것도 못 읽고 통과하는 것을 막는다
        assertThat(clamped).as("PageOffsets.clampPage로 만드는 곳(보호자 SOS·이상감지 이력)").isGreaterThanOrEqualTo(2);
        assertThat(fixedZero).as("PageRequest.of(0, ...) 고정 사용처").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("AdminPaging.of는 PageOffsets.clampPage로 page를 보정한다")
    void AdminPaging은_보정을_거친다() throws IOException {
        String adminPaging = Files.readString(
                MAIN.resolve("kr/silverbridge/main/domain/admin/support/AdminPaging.java"));
        Matcher m = PAGE_REQUEST_OF.matcher(adminPaging);
        int count = 0;
        while (m.find()) {
            assertThat(m.group(1).trim()).startsWith("PageOffsets.clampPage(");
            count++;
        }
        assertThat(count).isEqualTo(2);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String where(Path file, int index) {
        return MAIN.relativize(file) + ":" + (index + 1);
    }
}
