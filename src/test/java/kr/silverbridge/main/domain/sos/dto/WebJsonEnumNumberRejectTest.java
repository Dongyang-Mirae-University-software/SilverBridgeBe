package kr.silverbridge.main.domain.sos.dto;

import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.sos.entity.SosAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 웹 요청 JSON(Jackson 3)이 enum 칸의 숫자를 순번으로 받지 않는지 - 실제 application.yaml 설정으로 확인한다.
 * (최종 QA CONN-A26 {"sosAction":0} 이 CALL_119 로, CONN-A31 {"timeSlot":1} 이 LUNCH 로 저장되던 회귀, 2026-11-15)
 */
class WebJsonEnumNumberRejectTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yaml"));
                    sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

    @Test
    @DisplayName("enum 칸에 숫자(0·1)를 보내면 거절, 글자 값은 그대로 받는다")
    void 숫자_enum_거절() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);

            assertThatThrownBy(() -> mapper.readValue("{\"sosAction\":0}", SosSettingUpdateRequest.class))
                    .isInstanceOf(DatabindException.class);
            assertThatThrownBy(() -> mapper.readValue("1", MedicationTimeSlot.class))
                    .isInstanceOf(DatabindException.class);
            // 숫자를 글자로 보낸 것("0")·음수·범위 밖도 순번으로 받지 않는다(2026-11-16 점검 L-1)
            for (String json : List.of("{\"sosAction\":\"0\"}", "{\"sosAction\":\"1\"}", "{\"sosAction\":-1}",
                    "{\"sosAction\":99}")) {
                assertThatThrownBy(() -> mapper.readValue(json, SosSettingUpdateRequest.class)).as(json)
                        .isInstanceOf(DatabindException.class);
            }

            assertThat(mapper.readValue("{\"sosAction\":\"CALL_119\"}", SosSettingUpdateRequest.class).sosAction())
                    .isEqualTo(SosAction.CALL_119);
            assertThat(mapper.readValue("\"LUNCH\"", MedicationTimeSlot.class)).isEqualTo(MedicationTimeSlot.LUNCH);
        });
    }
}
