package kr.silverbridge.main.domain.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복약 시간대 = 복용 시각 규칙의 Swagger 설명 고정 (2026-10-30 영향 범위 점검 M-1).
 *
 * <p>등록·수정은 시간대를 최종 시각으로 다시 정하고 어긋나도 400이 아니라 시각 기준이다. 설명에 없으면 클라이언트는
 * "보낸 timeSlot이 그대로 저장된다"고 읽는다 - 요청 DTO 필드 설명만 고치고 본문 설명을 빠뜨린 적이 있다.</p>
 */
class MedicationSlotFromTimeApiDocTest {

    private static String operationDescription(String methodName) {
        Method method = Arrays.stream(GuardianMedicationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Operation operation = method.getAnnotation(Operation.class);
        assertThat(operation).as("%s에 @Operation이 있어야 한다", methodName).isNotNull();
        return operation.description();
    }

    private static String schemaDescription(Class<? extends Record> type, String component) throws Exception {
        Schema schema = type.getDeclaredField(component).getAnnotation(Schema.class);
        assertThat(schema).isNotNull();
        return schema.description();
    }

    @Test
    @DisplayName("약 등록 설명은 doseTime을 보내면 시간대가 그 시각으로 정해지고 어긋나도 시각 기준임을 밝힌다")
    void createOperationDescription() {
        assertThat(operationDescription("create"))
                .contains("저장되는 시간대는 그 시각으로 정해집니다", "시각 기준", "04:00~10:59 아침");
    }

    @Test
    @DisplayName("약 수정 설명은 시간대가 최종 시각으로 다시 정해지고 이름·복용량·메모만 고치면 그대로임을 밝힌다")
    void updateOperationDescription() {
        assertThat(operationDescription("update"))
                .contains("최종 복용 시각으로 다시 정해집니다", "시각 기준", "이름·복용량·메모만 보내면 시간대는 바뀌지 않습니다");
    }

    @Test
    @DisplayName("요청 DTO의 timeSlot 설명도 같은 규칙을 밝힌다")
    void requestTimeSlotDescriptions() throws Exception {
        assertThat(schemaDescription(MedicationCreateRequest.class, "timeSlot")).contains("시각으로 정해진다");
        assertThat(schemaDescription(MedicationUpdateRequest.class, "timeSlot")).contains("최종 시각으로 정해진다");
    }
}
