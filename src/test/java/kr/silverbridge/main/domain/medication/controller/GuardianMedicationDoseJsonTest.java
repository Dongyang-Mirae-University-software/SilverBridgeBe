package kr.silverbridge.main.domain.medication.controller;

import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 복용량의 실제 HTTP 직렬화(Boot 4 = Jackson 3). DB의 {@code 1.0}이 그대로 {@code "doseAmount":1.0}으로 나가지 않고
 * 정수는 {@code 1}, 반 알은 {@code 0.5}로 나간다. 일괄 등록은 배열로 나가고, 요청 바디의 정수·소수가 그대로 서비스로 간다.
 */
@ExtendWith(MockitoExtension.class)
class GuardianMedicationDoseJsonTest {

    @Mock private GuardianMedicationService guardianMedicationService;
    @Mock private GuardianMedicationSettingService guardianMedicationSettingService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("GD0001", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new GuardianMedicationController(guardianMedicationService, guardianMedicationSettingService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static MedicationItem item(String storedAmount, LocalTime time) {
        Medication medication = Medication.builder().wardId("WD0001").createdBy("GD0001").name("약")
                .timeSlot(MedicationTimeSlot.fromDoseTime(time)).doseTime(time).doseAmount(new BigDecimal(storedAmount)).build();
        return MedicationItem.of(medication, null);
    }

    @Test
    @DisplayName("일괄 등록 응답: 저장값 1.0 → 1, 0.5 → 0.5 (1.0 으로 바뀌지 않는다)")
    void batchResponseAmounts() throws Exception {
        when(guardianMedicationService.createBatch(eq("GD0001"), eq("WD0001"), any())).thenReturn(List.of(
                item("1.0", LocalTime.of(8, 0)), item("0.5", LocalTime.of(8, 0)), item("10.0", LocalTime.of(19, 0))));

        String body = mvc().perform(post("/api/guardian/ward/WD0001/medication/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"drugs":[{"name":"약","doseAmount":1},{"name":"약2","doseAmount":0.5}],
                                 "times":[{"doseTime":"08:00:00"}],"memo":null}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"doseAmount\":1,", "\"doseAmount\":0.5,", "\"doseAmount\":10,")
                .doesNotContain("1.0", "1E+1");
        verify(guardianMedicationService).createBatch(eq("GD0001"), eq("WD0001"), any());
    }

    @Test
    @DisplayName("단건 등록: 0.3 은 400, 정수 2 는 그대로 통과")
    void createValidation() throws Exception {
        mvc().perform(post("/api/guardian/ward/WD0001/medication").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"약\",\"timeSlot\":\"MORNING\",\"doseAmount\":0.3}"))
                .andExpect(status().isBadRequest());
        when(guardianMedicationService.create(any(), any(), any())).thenReturn(item("2", LocalTime.of(8, 0)));
        mvc().perform(post("/api/guardian/ward/WD0001/medication").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"약\",\"timeSlot\":\"MORNING\",\"doseAmount\":2}"))
                .andExpect(status().isOk());
    }
}
