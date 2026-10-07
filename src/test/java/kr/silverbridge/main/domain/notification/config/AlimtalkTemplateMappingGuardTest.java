package kr.silverbridge.main.domain.notification.config;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 {@code application.yaml}의 알림톡 템플릿 매핑을 고정한다(2026-10-07 점검 테스트 제안 2).
 *
 * <p>알림톡은 승인된 문구로만 보낼 수 있다. 본인용 이상감지·재촉·동수 안내·복약은 승인 템플릿이 없거나(또는 다발성
 * 메시지라 별도 승인이 필요해) 매핑을 두면 문구가 어긋난 발송이 되어 발신 프로필 차단 대상이 된다. 채널 단위 테스트는
 * 수동으로 만든 properties만 보므로, 설정 파일에 키가 슬쩍 추가되는 것은 여기서만 막힌다.</p>
 */
class AlimtalkTemplateMappingGuardTest {

    private static AlimtalkProperties bindFromApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new PropertiesPropertySource("application", yaml.getObject()));
        // ${ENV:default} 자리표시자는 기본값으로 풀린다(환경변수가 없는 테스트 환경) - 키 구성만 본다
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("notification.alimtalk", AlimtalkProperties.class)
                .orElseThrow(() -> new AssertionError("notification.alimtalk 설정이 없다"));
    }

    @Test
    @DisplayName("알림톡 템플릿은 이상감지 보호자용(ANOMALY_DETECTED) 하나뿐이다")
    void 템플릿_매핑은_이상감지_보호자용뿐() {
        Set<String> mapped = bindFromApplicationYaml().getTemplates().keySet();

        assertThat(mapped).containsExactly(NotificationType.ANOMALY_DETECTED.name());
    }

    @Test
    @DisplayName("본인용·재촉·동수 안내·복약·SOS에는 알림톡 템플릿을 매핑하지 않는다")
    void 승인_문구가_없는_종류는_매핑이_없다() {
        Set<String> mapped = bindFromApplicationYaml().getTemplates().keySet();

        assertThat(mapped).doesNotContain(
                NotificationType.ANOMALY_DETECTED_SELF.name(),
                NotificationType.ANOMALY_REVIEW_REQUIRED.name(),
                NotificationType.ANOMALY_REVIEW_CONFLICTED.name(),
                NotificationType.MEDICATION_REMINDER.name(),
                NotificationType.MEDICATION_MISSED.name(),
                NotificationType.MEDICATION_STOPPED.name(),
                NotificationType.WARD_SOS.name());
    }

    @Test
    @DisplayName("이상감지 템플릿 변수는 알림 data 키와 같은 이름이고 유형 라벨 변수를 쓴다(흉기·낙상도 같은 템플릿)")
    void 이상감지_템플릿_변수() {
        AlimtalkProperties.Template template = bindFromApplicationYaml().getTemplates()
                .get(NotificationType.ANOMALY_DETECTED.name());

        assertThat(template.getVariables())
                .containsExactly("wardName", "location", "detectedTypeLabel", "detectedAt");
    }
}
