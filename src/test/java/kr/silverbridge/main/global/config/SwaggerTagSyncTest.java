package kr.silverbridge.main.global.config;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨트롤러 {@code @Tag}와 {@link SwaggerConfig} 태그 목록이 어긋나지 않게 고정한다 (2026-10-27).
 *
 * <p>목록에 없는 태그는 설명 없이 Swagger UI 맨 뒤에 따로 붙는다. 2026-10-07 이후 생긴 컨트롤러 6개가
 * 그 상태로 남아 있었다. 스프링 컨텍스트 없이 클래스패스 스캔으로 검사한다.</p>
 */
class SwaggerTagSyncTest {

    private static final String BASE_PACKAGE = "kr.silverbridge.main";
    private static final List<String> ROLE_ORDER = List.of("공통", "피보호자", "보호자", "관리자");
    private static final Pattern NAME_RULE = Pattern.compile("^(공통|피보호자|보호자|관리자) - .+$");

    @Test
    @DisplayName("모든 @RestController에 @Tag(name)이 있다")
    void 모든_컨트롤러에_Tag() {
        Map<String, String> tags = controllerTags();

        assertThat(tags.entrySet())
                .as("@Tag가 없거나 name이 빈 컨트롤러")
                .filteredOn(e -> e.getValue() == null || e.getValue().isBlank())
                .isEmpty();
    }

    @Test
    @DisplayName("컨트롤러가 쓰는 태그 집합과 SwaggerConfig.tags() 집합이 양방향으로 같다")
    void 컨트롤러_태그와_등록_목록_일치() {
        Set<String> used = usedTagNames();
        Set<String> registered = new TreeSet<>(registeredTagNames());

        assertThat(used).as("SwaggerConfig.tags()에 등록되지 않은 태그").isSubsetOf(registered);
        assertThat(registered).as("쓰는 컨트롤러가 없는 등록 태그").isSubsetOf(used);
    }

    @Test
    @DisplayName("SwaggerConfig.tags()에 같은 이름이 두 번 들어 있지 않다")
    void 등록_태그_중복_없음() {
        List<String> names = registeredTagNames();

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("모든 태그 이름이 \"<공통|피보호자|보호자|관리자> - <도메인>\" 규칙을 따른다")
    void 태그_이름_규칙() {
        Set<String> all = new TreeSet<>(registeredTagNames());
        all.addAll(usedTagNames());

        assertThat(all).allSatisfy(name -> assertThat(name).matches(NAME_RULE));
    }

    @Test
    @DisplayName("tags() 순서가 공통 → 피보호자 → 보호자 → 관리자 구역을 지킨다")
    void 역할_구역_순서() {
        List<String> names = registeredTagNames();

        List<Integer> roleIndexes = names.stream()
                .map(name -> ROLE_ORDER.indexOf(name.substring(0, name.indexOf(" - "))))
                .toList();
        assertThat(roleIndexes).as("역할 구역 순서: %s", names).isSorted();
        assertThat(roleIndexes).as("네 역할 모두 태그가 있다").containsAll(List.of(0, 1, 2, 3));
    }

    /** @Tag가 없는 컨트롤러는 첫 테스트가 잡으므로 여기서는 뺀다. */
    private static Set<String> usedTagNames() {
        Set<String> names = new TreeSet<>();
        controllerTags().values().stream().filter(Objects::nonNull).forEach(names::add);
        return names;
    }

    private static List<String> registeredTagNames() {
        return new SwaggerConfig().openAPI().getTags().stream()
                .map(io.swagger.v3.oas.models.tags.Tag::getName)
                .toList();
    }

    /** 컨트롤러 FQCN → @Tag name (없으면 null). */
    private static Map<String, String> controllerTags() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> classNames = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            classNames.add(candidate.getBeanClassName());
        }
        // 테스트 소스의 검증용 컨트롤러(예: ...Test$ProbeController)는 Swagger에 실리지 않으므로 뺀다
        URL mainOutput = codeLocation(SwaggerConfig.class);
        Map<String, String> result = new LinkedHashMap<>();
        for (String className : classNames) {
            Class<?> type = ClassUtils.resolveClassName(className, SwaggerTagSyncTest.class.getClassLoader());
            if (!mainOutput.equals(codeLocation(type))) {
                continue;
            }
            Tag tag = type.getAnnotation(Tag.class);
            result.put(className, tag == null ? null : tag.name());
        }
        // 스캔이 비어 검사가 헛돌지 않게 - 2026-10-27 기준 37개
        assertThat(result).as("스캔된 @RestController").hasSizeGreaterThanOrEqualTo(30);
        return result;
    }

    private static URL codeLocation(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
