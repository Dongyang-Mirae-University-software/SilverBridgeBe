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
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반 알(0.5 단위) 검증과 일괄 등록 요청 검증(2026-10-22). 위반 = 컨트롤러에서 400.
 * 기존 FE가 보내는 정수 JSON(1, 2)도 그대로 받는지 실제 웹 변환기(Jackson 3)로 읽어 확인한다.
 */
class MedicationHalfDoseBatchValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static MedicationCreateRequest create(String amount) {
        return new MedicationCreateRequest("혈압약", MedicationTimeSlot.MORNING, null,
                amount == null ? null : new BigDecimal(amount), null);
    }

    private static MedicationUpdateRequest update(String amount) {
        return new MedicationUpdateRequest(null, null, null, new BigDecimal(amount), null);
    }

    private static Set<String> fieldsOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1", "1.5", "99"})
    @DisplayName("등록·수정: 0.5 단위 0.5~99 는 통과")
    void halfStepAccepted(String amount) {
        assertThat(validator.validate(create(amount))).isEmpty();
        assertThat(validator.validate(update(amount))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3", "0", "100", "1.2", "-0.5"})
    @DisplayName("등록·수정: 0.3·0·100 등은 doseAmount 위반(400)")
    void invalidRejected(String amount) {
        assertThat(fieldsOf(validator.validate(create(amount)))).containsExactly("doseAmount");
        assertThat(fieldsOf(validator.validate(update(amount)))).containsExactly("doseAmount");
    }

    @Test
    @DisplayName("등록: 생략하면 1, 정수 JSON(1·2)·0.5 JSON 모두 그대로 읽힌다(하위 호환)")
    void integerJsonStillAccepted() throws Exception {
        assertThat(create(null).resolveDoseAmount()).isEqualByComparingTo("1");
        MedicationCreateRequest integer = JSON.readValue(
                "{\"name\":\"혈압약\",\"timeSlot\":\"MORNING\",\"doseAmount\":2}", MedicationCreateRequest.class);
        MedicationCreateRequest half = JSON.readValue(
                "{\"name\":\"혈압약\",\"timeSlot\":\"MORNING\",\"doseAmount\":0.5}", MedicationCreateRequest.class);
        assertThat(integer.resolveDoseAmount()).isEqualByComparingTo("2");
        assertThat(validator.validate(integer)).isEmpty();
        assertThat(half.resolveDoseAmount()).isEqualByComparingTo("0.5");
        assertThat(validator.validate(half)).isEmpty();
    }

    // ─── 일괄 등록 ──────────────────────────────────────────────

    private static MedicationBatchCreateRequest batch(int drugs, int times) {
        List<MedicationBatchCreateRequest.Drug> d = new ArrayList<>();
        for (int i = 0; i < drugs; i++) {
            d.add(new MedicationBatchCreateRequest.Drug("약" + i, BigDecimal.ONE));
        }
        List<MedicationBatchCreateRequest.Time> t = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            t.add(new MedicationBatchCreateRequest.Time(LocalTime.of(6 + i, 0)));
        }
        return new MedicationBatchCreateRequest(d, t, null);
    }

    @Test
    @DisplayName("일괄: 약 1~20 · 시각 1~6 은 통과, 0개·21개·7개는 400")
    void batchSizeLimits() {
        assertThat(validator.validate(batch(20, 6))).isEmpty();
        assertThat(validator.validate(batch(1, 1))).isEmpty();
        assertThat(fieldsOf(validator.validate(batch(21, 1)))).containsExactly("drugs");
        assertThat(fieldsOf(validator.validate(batch(0, 1)))).containsExactly("drugs");
        assertThat(fieldsOf(validator.validate(batch(1, 7)))).containsExactly("times");
        assertThat(fieldsOf(validator.validate(batch(1, 0)))).containsExactly("times");
        assertThat(fieldsOf(validator.validate(new MedicationBatchCreateRequest(null, null, null))))
                .containsExactlyInAnyOrder("drugs", "times");
    }

    @Test
    @DisplayName("일괄: 항목 안의 이름(보이지 않는 문자·100자 초과)·복용량(0.3)·빈 시각·빈 항목은 위반")
    void batchItemValidation() {
        MedicationBatchCreateRequest request = new MedicationBatchCreateRequest(
                java.util.Arrays.asList(
                        new MedicationBatchCreateRequest.Drug("정상약", new BigDecimal("0.5")),
                        new MedicationBatchCreateRequest.Drug("​", null),
                        new MedicationBatchCreateRequest.Drug("가".repeat(101), null),
                        new MedicationBatchCreateRequest.Drug("감기약", new BigDecimal("0.3")),
                        null),
                Collections.singletonList(new MedicationBatchCreateRequest.Time(null)),
                "메모\u0000");

        assertThat(fieldsOf(validator.validate(request))).contains(
                "drugs[1].name", "drugs[2].name", "drugs[3].doseAmount", "drugs[4].<list element>",
                "times[0].doseTime", "memo");
    }
}
