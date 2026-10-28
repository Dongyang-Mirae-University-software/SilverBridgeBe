package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalItem;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalWeekdayHours;
import kr.silverbridge.main.domain.hospital.client.ReservationAdminClient;
import kr.silverbridge.main.global.audit.AdminAuditLogService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 제휴 병원 점심시간·휴진 요일 중계(2026-10-28) - 검증, 본문 조립(빈 문자열 = 지우기), 응답 변환. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminHospitalScheduleTest {

    private static final String ADMIN = "ADM001";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LIST = """
            [{"id":1,"name":"서울튼튼정형외과","region":"서울 강남구","address":"서울 강남구 테헤란로 1","phone":"02-2001-0001",
              "description":"","openTime":"09:00","closeTime":"18:00","breakStart":"12:30","breakEnd":"13:30",
              "closedWeekdays":[0,6,9,"x",6],"weekdayHours":[{"weekday":6,"openTime":"09:00","closeTime":"13:00"},{"weekday":9}],
              "bookingAvailable":true,"deleted":false,"departments":[]},
             {"id":2,"name":"부산바른내과","region":"부산","address":"부산 1","phone":"051-200-0001",
              "description":"","openTime":"09:00","closeTime":"18:00","bookingAvailable":true,"deleted":false,"departments":[]}]
            """;

    @Mock
    private ReservationAdminClient client;
    @Mock
    private AdminAuditLogService auditLogService;

    private AdminHospitalService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminHospitalService(client, auditLogService);
        when(client.listHospitals()).thenReturn(MAPPER.readTree(LIST));
        when(client.withinCall(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(client.updateHospital(anyLong(), anyMap())).thenReturn(MAPPER.readTree("{\"id\":1,\"departments\":[]}"));
        when(client.createHospital(anyMap())).thenReturn(MAPPER.readTree("{\"id\":9,\"departments\":[]}"));
    }

    private static AdminHospitalUpdateRequest update(String open, String close, String breakStart, String breakEnd, List<Integer> days) {
        return new AdminHospitalUpdateRequest(null, null, null, null, null, open, close, null, breakStart, breakEnd, days, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentUpdateBody() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).updateHospital(eq(1L), body.capture());
        return body.getValue();
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((CustomException) e).getErrorCode();
    }

    @Test
    @DisplayName("목록: 점심시간·휴진 요일을 싣고, 요일은 0~6 숫자만(중복·형식 이상은 버림), 없는 병원은 null·빈 배열")
    void list_maps_schedule() {
        List<AdminHospitalItem> items = service.getHospitals(ADMIN, null, null, null, 0, 20).content();
        AdminHospitalItem withLunch = items.stream().filter(h -> h.id() == 1).findFirst().orElseThrow();
        assertThat(withLunch.breakStart()).isEqualTo("12:30");
        assertThat(withLunch.breakEnd()).isEqualTo("13:30");
        assertThat(withLunch.closedWeekdays()).containsExactly(0, 6);
        AdminHospitalItem plain = items.stream().filter(h -> h.id() == 2).findFirst().orElseThrow();
        assertThat(plain.breakStart()).isNull();
        assertThat(plain.closedWeekdays()).isEmpty();
    }

    @Test
    @DisplayName("수정: 점심시간·휴진 요일만 보내도 되고, 빈 문자열은 null 로(지우기), 요일은 정렬해 보낸다")
    void update_sends_schedule() {
        service.update(ADMIN, 1, update(null, null, "12:00", "13:00", List.of(6, 0)));
        assertThat(sentUpdateBody()).containsEntry("breakStart", "12:00").containsEntry("breakEnd", "13:00")
                .containsEntry("closedWeekdays", List.of(0, 6));
    }

    @Test
    @DisplayName("수정: 빈 문자열 두 개는 점심시간 지우기(null 전송)")
    void update_clears_break() {
        service.update(ADMIN, 1, update(null, null, "", "", null));
        Map<String, Object> body = sentUpdateBody();
        assertThat(body).containsKeys("breakStart", "breakEnd");
        assertThat(body.get("breakStart")).isNull();
        assertThat(body.get("breakEnd")).isNull();
    }

    @Test
    @DisplayName("수정: 한쪽만·진료 시간 밖·시작 >= 끝인 점심시간은 400 HOSPITAL_INVALID_BREAK, 호출·감사 로그 없음")
    void update_rejects_bad_break() {
        for (String[] bad : Arrays.asList(new String[]{"12:00", null}, new String[]{"12:00", ""}, new String[]{"08:30", "09:30"},
                new String[]{"13:00", "12:00"}, new String[]{"17:30", "18:30"})) {
            assertThatThrownBy(() -> service.update(ADMIN, 1, update(null, null, bad[0], bad[1], null)))
                    .extracting(AdminHospitalScheduleTest::codeOf).isEqualTo(ErrorCode.HOSPITAL_INVALID_BREAK);
        }
        verify(client, never()).updateHospital(anyLong(), anyMap());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("수정: 진료 시간만 줄여 기존 점심시간(12:30~13:30)이 밖으로 밀려나면 400")
    void update_hours_against_existing_break() {
        assertThatThrownBy(() -> service.update(ADMIN, 1, update(null, "13:00", null, null, null)))
                .extracting(AdminHospitalScheduleTest::codeOf).isEqualTo(ErrorCode.HOSPITAL_INVALID_BREAK);
        service.update(ADMIN, 1, update(null, "17:00", null, null, null)); // 점심시간을 포함하면 통과
        assertThat(sentUpdateBody()).containsOnlyKeys("closeTime");
    }

    @Test
    @DisplayName("요일별 진료 시간: 목록에 싣고(형식 이상 항목은 버림), 수정은 요일순으로 보내며 같은 요일·시작 >= 끝은 400")
    void weekday_hours() {
        AdminHospitalItem withLunch = service.getHospitals(ADMIN, null, null, null, 0, 20).content().stream()
                .filter(h -> h.id() == 1).findFirst().orElseThrow();
        assertThat(withLunch.weekdayHours()).containsExactly(new AdminHospitalWeekdayHours(6, "09:00", "13:00"));

        service.update(ADMIN, 1, new AdminHospitalUpdateRequest(null, null, null, null, null, null, null, null, null, null, null,
                List.of(new AdminHospitalWeekdayHours(6, "10:00", "14:00"), new AdminHospitalWeekdayHours(3, "09:00", "12:00"))));
        assertThat(sentUpdateBody().get("weekdayHours")).isEqualTo(List.of(
                Map.of("weekday", 3, "openTime", "09:00", "closeTime", "12:00"),
                Map.of("weekday", 6, "openTime", "10:00", "closeTime", "14:00")));

        for (List<AdminHospitalWeekdayHours> bad : List.of(
                List.of(new AdminHospitalWeekdayHours(6, "09:00", "13:00"), new AdminHospitalWeekdayHours(6, "10:00", "12:00")),
                List.of(new AdminHospitalWeekdayHours(6, "13:00", "09:00")))) {
            assertThatThrownBy(() -> service.update(ADMIN, 1, new AdminHospitalUpdateRequest(null, null, null, null, null, null, null,
                    null, null, null, null, bad)))
                    .extracting(AdminHospitalScheduleTest::codeOf).isEqualTo(ErrorCode.HOSPITAL_INVALID_HOURS);
        }
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(new AdminHospitalUpdateRequest(null, null, null, null, null, null, null, null, null, null, null,
                List.of(new AdminHospitalWeekdayHours(7, "09:00", "13:00"))))).isNotEmpty();
    }

    @Test
    @DisplayName("수정: 휴진 요일 중복은 400 INVALID_INPUT")
    void update_rejects_duplicate_days() {
        assertThatThrownBy(() -> service.update(ADMIN, 1, update(null, null, null, null, List.of(0, 0))))
                .extracting(AdminHospitalScheduleTest::codeOf).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("등록: 점심시간·휴진 요일을 함께 보내고, 없으면 칸을 싣지 않는다")
    void create_sends_schedule() {
        service.create(ADMIN, new AdminHospitalCreateRequest("새 병원", "서울", "서울 새주소 1", "02-111-2222", null,
                "09:00", "18:00", true, List.of("내과"), "12:30", "13:30", List.of(0), null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).createHospital(body.capture());
        assertThat(body.getValue()).containsEntry("breakStart", "12:30").containsEntry("closedWeekdays", List.of(0));

        service.create(ADMIN, new AdminHospitalCreateRequest("새 병원2", "서울", "서울 새주소 2", "02-111-2223", null,
                "09:00", "18:00", true, List.of("내과")));
        verify(client, org.mockito.Mockito.times(2)).createHospital(body.capture());
        assertThat(body.getValue()).doesNotContainKeys("breakStart", "breakEnd", "closedWeekdays");
    }

    @Test
    @DisplayName("등록: 진료 시간 밖 점심시간은 400")
    void create_rejects_bad_break() {
        assertThatThrownBy(() -> service.create(ADMIN, new AdminHospitalCreateRequest("새 병원", "서울", "서울 새주소 1", "02-111-2222",
                null, "09:00", "12:00", true, List.of("내과"), "12:30", "13:30", null, null)))
                .extracting(AdminHospitalScheduleTest::codeOf).isEqualTo(ErrorCode.HOSPITAL_INVALID_BREAK);
        verify(client, never()).createHospital(anyMap());
    }

    @Test
    @DisplayName("DTO: 요일 0~6 밖·형식 틀린 시각은 검증 오류")
    void dto_validation() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(validator.validate(update(null, null, "25:00", "13:00", null))).isNotEmpty();
        assertThat(validator.validate(update(null, null, null, null, List.of(7)))).isNotEmpty();
        assertThat(validator.validate(update(null, null, "", "", List.of()))).isEmpty();
        assertThat(update(null, null, null, null, List.of()).isEmpty()).isFalse();
    }
}
