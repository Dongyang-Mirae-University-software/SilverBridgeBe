package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.camera.controller.WardCameraController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카메라 사용 중지(isActive)의 Swagger 설명 고정 (ANOM-G08).
 *
 * <p>{@code camera.is_active}는 표시용이라 감지·알림을 끄지 않는다(2026-10-02 결정). 이름만 보면 "끄면 감시도 멈춘다"로
 * 읽히므로 API 문서가 이를 밝혀야 한다 - 설명이 빠지면 FE·QA가 반대로 이해한다.</p>
 */
class CameraIsActiveApiDocTest {

    private static String schemaDescription(Class<? extends Record> type, String component) throws Exception {
        Schema schema = type.getDeclaredField(component).getAnnotation(Schema.class);
        assertThat(schema).as("%s.%s에 @Schema가 있어야 한다", type.getSimpleName(), component).isNotNull();
        return schema.description();
    }

    @Test
    @DisplayName("수정 요청 isActive 설명은 사용 중지해도 감지·알림이 계속되고 보호자 화면에서만 숨겨짐을 밝힌다")
    void updateRequestIsActiveDescription() throws Exception {
        assertThat(schemaDescription(CameraUpdateRequest.class, "isActive"))
                .contains("감지", "알림", "계속", "보호자", "숨겨");
    }

    @Test
    @DisplayName("응답 isActive 설명도 감지·알림이 계속됨을 밝힌다 (피보호자·보호자 응답)")
    void responseIsActiveDescriptions() throws Exception {
        assertThat(schemaDescription(CameraResponse.class, "isActive")).contains("감지", "알림", "계속");
        assertThat(schemaDescription(GuardianCameraView.class, "isActive")).contains("감지", "알림", "계속");
    }

    @Test
    @DisplayName("PATCH /api/ward/camera/{id} 설명은 사용 중지해도 감지·알림이 계속됨을 밝힌다")
    void patchOperationDescription() {
        Method update = Arrays.stream(WardCameraController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("update"))
                .findFirst()
                .orElseThrow();
        Operation operation = update.getAnnotation(Operation.class);

        assertThat(operation).isNotNull();
        assertThat(operation.description())
                .contains("사용 중지해도 화재 감지와 알림은 계속됩니다(보호자 화면에서만 숨겨짐)");
    }
}
