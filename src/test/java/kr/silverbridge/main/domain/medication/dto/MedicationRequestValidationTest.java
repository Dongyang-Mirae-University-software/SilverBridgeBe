package kr.silverbridge.main.domain.medication.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약 등록·수정 요청의 이름·메모 검증(MED-G16).
 *
 * <p>제로폭 공백(U+200B)만 넣은 이름은 {@code @NotBlank}를 통과해 이름이 비어 보이는 약 카드·알림
 * (" 드실 시간입니다.")이 생겼다. 위반 = 컨트롤러에서 400이다.</p>
 */
@DisplayName("복약 요청 DTO 검증")
class MedicationRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"​", "​‌‍", "﻿", "ㅤ", "  　 "})
    @DisplayName("등록 - 보이지 않는 문자만으로 된 이름은 거절")
    void 등록_보이지않는_이름_거절(String name) {
        assertThat(fieldsOf(validator.validate(create(name, null)))).contains("name");
    }

    @Test
    @DisplayName("등록 - NUL 등 제어문자가 섞인 이름·메모는 거절")
    void 등록_제어문자_거절() {
        assertThat(fieldsOf(validator.validate(create("혈압\u0000약", null)))).contains("name");
        assertThat(fieldsOf(validator.validate(create("혈압약", "식후\u0000")))).contains("memo");
    }

    @Test
    @DisplayName("등록 - 앞뒤 공백·제로폭이 섞여도 보이는 글자가 있으면 통과(저장 시 정리), 메모 줄바꿈도 통과")
    void 등록_정상() {
        assertThat(validator.validate(create(" 혈압약​ ", "식후\n30분"))).isEmpty();
        assertThat(validator.validate(create("혈압약", ""))).isEmpty();
    }

    @Test
    @DisplayName("수정 - 이름 미전달(null)은 미변경이라 통과, 제로폭만 보내면 거절")
    void 수정_이름() {
        assertThat(validator.validate(new MedicationUpdateRequest(null, null, null, null, null))).isEmpty();
        assertThat(fieldsOf(validator.validate(new MedicationUpdateRequest("​", null, null, null, null))))
                .contains("name");
        assertThat(validator.validate(new MedicationUpdateRequest("당뇨약", null, null, null, ""))).isEmpty();
    }

    private static MedicationCreateRequest create(String name, String memo) {
        return new MedicationCreateRequest(name, MedicationTimeSlot.MORNING, null, 1, memo);
    }

    private static Set<String> fieldsOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
