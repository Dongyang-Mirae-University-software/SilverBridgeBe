package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.controller.GuardianMedicationController;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.notification.controller.GuardianNotificationTypeSettingController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보호자 복약 알림 토글의 Swagger 설명 고정 (2026-11-04 영향 범위 점검 M-1).
 *
 * <p>보호자의 복약 토글(계정·피보호자별)은 미복용 요약뿐 아니라 <b>피보호자 기기로 가는 "약 드실 시간" 알림</b>도
 * 켜고 끈다. 설명이 "미복용 요약만"으로 남으면 FE가 피보호자별 토글을 "내 요약만"으로 붙여, 보호자가 모르는 사이
 * 어르신 알림을 끄게 된다.</p>
 */
class MedicationToggleApiDocTest {

    private static String schemaDescription(Class<? extends Record> type, String component) throws Exception {
        Schema schema = type.getDeclaredField(component).getAnnotation(Schema.class);
        assertThat(schema).as("%s.%s에 @Schema가 있어야 한다", type.getSimpleName(), component).isNotNull();
        return schema.description();
    }

    private static String operationDescription(Class<?> controller, String methodName) {
        Operation operation = Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Operation.class);
        assertThat(operation).as("%s.%s에 @Operation이 있어야 한다", controller.getSimpleName(), methodName).isNotNull();
        return operation.description();
    }

    @Test
    @DisplayName("알림 종류 설정의 medication 설명은 피보호자 \"약 드실 시간\" 알림도 함께 켜고 끔을 밝힌다")
    void typeSettingMedicationDescriptions() throws Exception {
        assertThat(schemaDescription(GuardianNotificationTypeSettingResponse.class, "medication"))
                .contains("약 드실 시간", "함께");
        assertThat(schemaDescription(GuardianNotificationTypeSettingRequest.class, "medication"))
                .contains("약 드실 시간", "함께");
        assertThat(operationDescription(GuardianNotificationTypeSettingController.class, "getSettings"))
                .contains("약 드실 시간");
        assertThat(operationDescription(GuardianNotificationTypeSettingController.class, "updateSettings"))
                .contains("약 드실 시간", "멈춥니다", "한 명이라도");
    }

    @Test
    @DisplayName("피보호자별 미복용 요약 설정의 missedAlertEnabled 설명은 피보호자 \"약 드실 시간\" 알림 판정에도 쓰임을 밝힌다")
    void perWardMissedAlertDescriptions() throws Exception {
        assertThat(operationDescription(GuardianMedicationController.class, "getAlertSetting"))
                .contains("약 드실 시간");
        assertThat(operationDescription(GuardianMedicationController.class, "updateAlertSetting"))
                .contains("약 드실 시간", "멈춥니다");
        assertThat(schemaDescription(WardMedicationSummary.class, "missedAlertEnabled"))
                .contains("약 드실 시간");
    }
}
