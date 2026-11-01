package kr.silverbridge.main.domain.checkin.controller;

import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinQuestionsUpdateRequest;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.service.CheckinQuestionService;
import kr.silverbridge.main.domain.checkin.service.CheckinReportService;
import kr.silverbridge.main.domain.checkin.service.CheckinSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보호자 말벗 컨트롤러를 실제 HTTP 직렬화(웹 계층 Jackson)로 확인한다(2026-11-01 점검 M-3).
 * 시각 {@code "09:00"}은 {@code CheckinSlot}의 {@code @JsonCreator}·{@code @JsonValue}로 오가야 하고, 모르는 시각은 400이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class GuardianCheckinControllerHttpTest {

    private static final String GUARDIAN = "gA1b2C";

    @Mock private CheckinQuestionService questionService;
    @Mock private CheckinSettingService settingService;
    @Mock private CheckinReportService reportService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(GUARDIAN, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new GuardianCheckinController(questionService, settingService, reportService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("질문 변경: \"09:00\"이 CheckinSlot.AT_09로 읽힌다")
    void slotDeserialized() throws Exception {
        when(questionService.update(eq(GUARDIAN), eq("wA1b2C"), any()))
                .thenReturn(new CheckinQuestionsResponse(List.of()));

        mvc().perform(put("/api/guardian/ward/wA1b2C/checkin/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"slot\":\"09:00\",\"question\":\"잘 주무셨어요?\"}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        ArgumentCaptor<CheckinQuestionsUpdateRequest> request = ArgumentCaptor.forClass(CheckinQuestionsUpdateRequest.class);
        verify(questionService).update(eq(GUARDIAN), eq("wA1b2C"), request.capture());
        assertThat(request.getValue().questions().get(0).slot()).isEqualTo(CheckinSlot.AT_09);
        assertThat(request.getValue().questions().get(0).question()).isEqualTo("잘 주무셨어요?");
    }

    @Test
    @DisplayName("모르는 시각(\"10:00\")은 서비스에 닿지 않고 400")
    void unknownSlot() throws Exception {
        mvc().perform(put("/api/guardian/ward/wA1b2C/checkin/questions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"slot\":\"10:00\",\"question\":\"q\"}]}"))
                .andExpect(status().isBadRequest());
        verify(questionService, never()).update(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("질문 조회 응답의 시각은 \"09:00\" 문자열로 나간다")
    void slotSerialized() throws Exception {
        when(questionService.get(GUARDIAN, "wA1b2C")).thenReturn(new CheckinQuestionsResponse(List.of(
                new CheckinQuestionsResponse.Item(CheckinSlot.AT_09, "q", false))));

        mvc().perform(get("/api/guardian/ward/wA1b2C/checkin/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questions[0].slot").value("09:00"));
    }

    @Test
    @DisplayName("하루 조회: date=YYYY-MM-DD를 읽고, 형식이 틀리면 400")
    void dailyDateParam() throws Exception {
        mvc().perform(get("/api/guardian/checkin").param("wardId", "wA1b2C").param("date", "2026-11-01"))
                .andExpect(status().isOk());
        verify(reportService).daily(GUARDIAN, "wA1b2C", LocalDate.of(2026, 11, 1));

        mvc().perform(get("/api/guardian/checkin").param("wardId", "wA1b2C").param("date", "2026-13-01"))
                .andExpect(status().isBadRequest());
    }
}
