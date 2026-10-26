package kr.silverbridge.main.domain.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 제휴 병원 요청 DTO 검증 - 예약 서비스가 모양만 보는 칸(시각·전화)과 이름·주소 텍스트 규칙. */
class AdminHospitalRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static AdminHospitalCreateRequest create(String name, String phone, String open, List<String> departments) {
        return new AdminHospitalCreateRequest(name, "서울 강남구", "서울 강남구 1", phone, "소개\n둘째 줄", open, "18:00", true, departments);
    }

    private static Set<String> fields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("정상 등록 요청은 통과한다(소개 줄바꿈 허용)")
    void valid() {
        assertThat(validator.validate(create("서울튼튼정형외과", "02-2001-0001", "09:00", List.of("정형외과")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"02-2001-0001", "0220010001", "031-123-4567", "1588-1234", "15881234"})
    @DisplayName("전화: 지역번호형·대표번호형(하이픈 선택)은 통과")
    void phone_ok(String phone) {
        assertThat(validator.validate(create("병원", phone, "09:00", List.of("내과")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "abc-defg", "02-1", "+82-2-2001-0001", "02 2001 0001"})
    @DisplayName("전화: 형식이 아니면 phone 위반")
    void phone_bad(String phone) {
        assertThat(fields(validator.validate(create("병원", phone, "09:00", List.of("내과"))))).containsExactly("phone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"99:99", "24:00", "9:00", "09:60", "0900"})
    @DisplayName("시각: 실제 HH:mm이 아니면 openTime 위반(예약 서비스는 99:99도 받는다)")
    void time_bad(String time) {
        assertThat(fields(validator.validate(create("병원", "02-2001-0001", time, List.of("내과"))))).containsExactly("openTime");
    }

    @Test
    @DisplayName("이름: 보이지 않는 글자만·제어문자·100자 초과는 위반")
    void name_rules() {
        assertThat(fields(validator.validate(create("​ㅤ", "02-2001-0001", "09:00", List.of("내과"))))).contains("name");
        assertThat(fields(validator.validate(create("병\u0000원", "02-2001-0001", "09:00", List.of("내과"))))).contains("name");
        assertThat(fields(validator.validate(create("가".repeat(101), "02-2001-0001", "09:00", List.of("내과"))))).contains("name");
    }

    @Test
    @DisplayName("진료과: 0개·21개·빈 이름·31자는 위반")
    void departments_rules() {
        assertThat(fields(validator.validate(create("병원", "02-2001-0001", "09:00", List.of())))).contains("departments");
        assertThat(fields(validator.validate(create("병원", "02-2001-0001", "09:00", Collections.nCopies(21, "내과")))))
                .contains("departments");
        assertThat(validator.validate(create("병원", "02-2001-0001", "09:00", List.of(" ")))).isNotEmpty();
        assertThat(validator.validate(create("병원", "02-2001-0001", "09:00", List.of("가".repeat(31))))).isNotEmpty();
    }

    @Test
    @DisplayName("상태: 음수·상한 초과·공지 제어문자는 위반")
    void status_rules() {
        assertThat(fields(validator.validate(new AdminHospitalStatusRequest(null, null, -1, 1441, 10000, null, "a\u0007"))))
                .containsExactlyInAnyOrder("waitingCount", "averageWaitingMinutes", "todayReservationCount", "notice");
    }

    @Test
    @DisplayName("수정: 빈 이름(보이지 않는 글자)은 위반이고 null은 변경 안 함이라 통과")
    void update_rules() {
        assertThat(validator.validate(new AdminHospitalUpdateRequest(null, null, null, null, null, null, null, false))).isEmpty();
        assertThat(fields(validator.validate(new AdminHospitalUpdateRequest("　", null, null, null, null, null, null, null))))
                .contains("name");
    }
}
