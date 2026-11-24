package kr.silverbridge.main.domain.connection.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConnectionRequestDto.relation 입력 검증 (CONN-G14).
 * <p>
 * {@code @NotBlank}는 U+0020 이하만 공백으로 보아 제로폭 문자·NBSP·전각공백만 있는 관계값이 통과했다.
 * {@code @VisibleText}로 눈에 보이는 글자가 없으면 400이 나가는지 고정한다.
 */
class ConnectionRequestDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"​", " ", "　", "​　﻿"})
    @DisplayName("보이지 않는 문자만 있는 관계값 → relation 위반")
    void invisibleOnlyRelationRejected(String relation) {
        Set<ConstraintViolation<ConnectionRequestDto>> violations = validator.validate(dto(relation));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).contains("relation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"아들", "​며느리"})
    @DisplayName("보이는 글자가 있는 관계값 → 위반 없음 (보이지 않는 문자는 서비스가 정리해 저장)")
    void visibleRelationPasses(String relation) {
        assertThat(validator.validate(dto(relation))).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("어르신 호칭(wardRelation)은 선택값 - 없어도 위반 없음 (화면 업데이트 전 요청 호환)")
    void wardRelationIsOptional() {
        assertThat(validator.validate(dto("딸"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"엄마", "외할아버지", "​아내"})
    @DisplayName("보이는 글자가 있는 어르신 호칭 → 위반 없음")
    void visibleWardRelationPasses(String wardRelation) {
        assertThat(validator.validate(dto("딸", wardRelation))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"​", " ", "　", "가나다라마바사아자차카"})
    @DisplayName("보이지 않는 문자만 있거나 10자를 넘는 어르신 호칭 → wardRelation 위반")
    void invalidWardRelationRejected(String wardRelation) {
        Set<ConstraintViolation<ConnectionRequestDto>> violations = validator.validate(dto("딸", wardRelation));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("wardRelation");
    }

    private static ConnectionRequestDto dto(String relation) {
        return dto(relation, null);
    }

    private static ConnectionRequestDto dto(String relation, String wardRelation) {
        ConnectionRequestDto dto = new ConnectionRequestDto();
        ReflectionTestUtils.setField(dto, "targetId", "WD0001");
        ReflectionTestUtils.setField(dto, "relation", relation);
        ReflectionTestUtils.setField(dto, "wardRelation", wardRelation);
        return dto;
    }
}
