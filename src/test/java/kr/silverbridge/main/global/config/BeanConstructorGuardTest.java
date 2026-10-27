package kr.silverbridge.main.global.config;

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
 * 정책 고정 - 생성자가 둘 이상인 Spring 빈은 주입에 쓸 생성자에 {@code @Autowired}를 붙인다(2026-10-27 점검 L-4).
 *
 * <p>테스트용 생성자를 하나 더 두면 Spring이 고를 생성자가 없어 기본 생성자를 찾다가 <b>기동이 실패</b>한다.
 * 단위 테스트는 생성자를 직접 불러 이 결함을 잡지 못하고, 실제로 #387 배포 직후 두 서버의 api가 재시작을 반복했다(#388).
 * 컨텍스트 전체를 띄우는 테스트가 없어 소스를 훑어 막는다.</p>
 */
class BeanConstructorGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern BEAN_ANNOTATION = Pattern.compile(
            "^@(Component|Service|Repository|Controller|RestController|Configuration|ControllerAdvice|RestControllerAdvice)\\b",
            Pattern.MULTILINE);

    @Test
    @DisplayName("생성자가 둘 이상인 빈에는 @Autowired가 있다 - 없으면 기동 실패(No default constructor found)")
    void 다중_생성자_빈은_주입_생성자를_명시한다() throws IOException {
        List<String> violations = new ArrayList<>();
        int beans = 0;

        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            if (!BEAN_ANNOTATION.matcher(source).find()) {
                continue;
            }
            beans++;
            String className = file.getFileName().toString().replace(".java", "");
            Matcher ctor = Pattern.compile(
                    "^\\s*(?:public |protected |private )?" + Pattern.quote(className) + "\\s*\\(", Pattern.MULTILINE)
                    .matcher(source);
            int constructors = 0;
            while (ctor.find()) {
                constructors++;
            }
            if (constructors >= 2 && !source.contains("@Autowired")) {
                violations.add(MAIN.relativize(file) + " (생성자 " + constructors + "개)");
            }
        }

        assertThat(violations)
                .as("생성자가 여러 개인 Spring 빈은 주입에 쓸 생성자에 @Autowired를 붙일 것 - 위반 위치")
                .isEmpty();
        // 스캔이 실제로 빈을 읽었는지 - 경로가 틀려 아무것도 못 읽고 통과하는 것을 막는다
        assertThat(beans).as("스캔한 Spring 빈 수").isGreaterThan(100);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
