package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.controller.AdminAnomalyController;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyFeedbackItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalySummaryResponse;
import kr.silverbridge.main.domain.anomaly.service.AdminAnomalyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자는 이상감지 클립을 열람하지 않는다(2026-10-04 결정) - 관리자 응답 DTO·컨트롤러·서비스 어디에도 클립 정보가 없어야 한다.
 * 보호자 DTO({@code AnomalyIncidentItem})에 클립 필드를 더하면서 관리자 DTO로 같이 새지 않게 고정한다.
 */
class AdminAnomalyClipExposureTest {

    @ParameterizedTest
    @ValueSource(classes = {AdminAnomalyIncidentItem.class, AdminAnomalyFeedbackItem.class,
            AdminAnomalySummaryResponse.class})
    @DisplayName("관리자 응답 DTO에 clip·video 관련 필드가 없다")
    void 관리자_DTO_클립필드_없음(Class<?> dto) {
        Stream<String> names = dto.isRecord()
                ? Arrays.stream(dto.getRecordComponents()).map(RecordComponent::getName)
                : Arrays.stream(dto.getDeclaredFields()).map(Field::getName);

        assertThat(names.map(name -> name.toLowerCase(Locale.ROOT)))
                .as(dto.getSimpleName())
                .noneMatch(name -> name.contains("clip") || name.contains("video"));
    }

    @ParameterizedTest
    @ValueSource(classes = {AdminAnomalyController.class, AdminAnomalyService.class})
    @DisplayName("관리자 이상감지 컨트롤러·서비스는 클립을 다루지 않는다(메서드·의존성)")
    void 관리자_경로_클립_없음(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("clip"));
        assertThat(Arrays.stream(type.getDeclaredFields()).map(field -> field.getType().getSimpleName()))
                .noneMatch(name -> name.contains("Clip"));
    }
}
