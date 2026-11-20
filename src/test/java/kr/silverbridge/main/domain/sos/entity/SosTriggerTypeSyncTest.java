package kr.silverbridge.main.domain.sos.entity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.sos.controller.GuardianSosController;
import kr.silverbridge.main.domain.sos.controller.WardSosController;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.dto.SosTriggerRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOS 발생 경로({@link SosTriggerType})를 추가할 때 함께 바꿔야 하는 하드코딩 목록을 고정한다
 * (2026-11-20 CHATBOT_109 영향 범위 점검 L-1).
 *
 * <ul>
 *   <li>Swagger {@code allowableValues}(요청 {@link SosTriggerRequest}·응답 {@link SosHistoryItem})</li>
 *   <li>요청·이력 API 설명문의 경로 나열</li>
 *   <li>DB {@code sos_event.trigger_type VARCHAR(20)}(V39, CHECK 없음) - 이름 길이</li>
 * </ul>
 * 집계 칸({@code SosHistoryPage.Counts})은 {@code GuardianSosService.toCounts}의 default 없는 switch가 컴파일로 막는다.
 */
class SosTriggerTypeSyncTest {

    /** V39 {@code trigger_type VARCHAR(20)}. */
    private static final int COLUMN_LENGTH = 20;

    private static Set<String> enumNames() {
        return Arrays.stream(SosTriggerType.values()).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<String> allowableValues(Class<?> type, String field) throws NoSuchFieldException {
        Schema schema = type.getDeclaredField(field).getAnnotation(Schema.class);
        assertThat(schema).as(type.getSimpleName() + "." + field + " @Schema").isNotNull();
        return Set.of(schema.allowableValues());
    }

    private static String operationDescription(Class<?> controller, String method) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(method))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Operation.class)
                .description();
    }

    @Test
    @DisplayName("요청·응답 Swagger allowableValues가 enum 값 전체와 같다")
    void allowableValuesMatchEnum() throws Exception {
        assertThat(allowableValues(SosTriggerRequest.class, "triggerType")).isEqualTo(enumNames());
        assertThat(allowableValues(SosHistoryItem.class, "triggerType")).isEqualTo(enumNames());
    }

    @Test
    @DisplayName("모든 경로 이름이 trigger_type VARCHAR(20)에 들어간다")
    void namesFitColumn() {
        assertThat(SosTriggerType.values())
                .allSatisfy(type -> assertThat(type.name().length()).as(type.name()).isLessThanOrEqualTo(COLUMN_LENGTH));
    }

    @Test
    @DisplayName("SOS 발생·보호자 이력 API 설명에 모든 경로 이름이 적혀 있다")
    void descriptionsListAllNames() {
        String wardDescription = operationDescription(WardSosController.class, "triggerSos");
        String guardianDescription = operationDescription(GuardianSosController.class, "getSosHistory");

        assertThat(SosTriggerType.values()).allSatisfy(type -> {
            assertThat(wardDescription).as("WardSosController.triggerSos").contains(type.name());
            assertThat(guardianDescription).as("GuardianSosController.getSosHistory").contains(type.name());
        });
    }
}
