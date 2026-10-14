package kr.silverbridge.main.domain.camera.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 카메라 등록 Swagger가 전체 등록 상한(409 CAMERA_LIMIT_REACHED)을 밝히는지 고정한다 (2026-10-14 점검 L-1). */
class CameraTotalLimitApiDocTest {

    private static Method register() {
        return Arrays.stream(WardCameraController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("register"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("등록 설명과 409 응답 설명이 CAMERA_LIMIT_REACHED를 밝힌다")
    void registerDocumentsLimit() {
        Method register = register();

        assertThat(register.getAnnotation(Operation.class).description()).contains("CAMERA_LIMIT_REACHED");
        boolean in409 = Arrays.stream(register.getAnnotation(ApiResponses.class).value())
                .filter(response -> response.responseCode().equals("409"))
                .anyMatch(response -> response.description().contains("CAMERA_LIMIT_REACHED"));
        assertThat(in409).isTrue();
    }
}
