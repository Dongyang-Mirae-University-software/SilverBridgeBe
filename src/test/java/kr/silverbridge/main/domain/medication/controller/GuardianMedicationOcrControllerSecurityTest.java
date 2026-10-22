package kr.silverbridge.main.domain.medication.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.domain.medication.service.MedicationOcrService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약 봉투 OCR API - 실제 {@link SecurityConfig}·메서드 보안·전역 예외 처리를 거쳐 상태 코드를 확인한다.
 * 비로그인 401 / WARD·ADMIN 403(서비스 미호출) / GUARDIAN 200 / 서비스 오류 코드별 HTTP 상태.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
        GuardianMedicationOcrControllerSecurityTest.TestConfig.class,
        SecurityConfig.class
})
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class GuardianMedicationOcrControllerSecurityTest {

    private static final String URL = "/api/guardian/ward/WD0001/medication/ocr";
    private static final MockMultipartFile IMAGE =
            new MockMultipartFile("image", "a.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        GuardianMedicationOcrController guardianMedicationOcrController(MedicationOcrService service) {
            return new GuardianMedicationOcrController(service);
        }
    }

    @MockitoBean private MedicationOcrService medicationOcrService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private StringRedisTemplate redisTemplate;
    @MockitoBean private RateLimitService rateLimitService;

    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("비로그인 → 401, 서비스 미호출")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(multipart(URL).file(IMAGE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(medicationOcrService);
    }

    @Test
    @DisplayName("WARD·ADMIN → 403, 서비스 미호출(약 등록 초안은 보호자 몫)")
    void wardAndAdmin_403() throws Exception {
        mockMvc.perform(multipart(URL).file(IMAGE).with(user("WD0001").roles("WARD")))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(URL).file(IMAGE).with(user("AD0001").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(medicationOcrService);
    }

    @Test
    @DisplayName("GUARDIAN → 200 + Cache-Control no-store")
    void guardian_200_noStore() throws Exception {
        when(medicationOcrService.read(any(), any(), any())).thenReturn(new MedicationOcrResponse(false, List.of()));

        mockMvc.perform(multipart(URL).file(IMAGE).with(user("GD0001").roles("GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.readable").value(false));
    }

    @Test
    @DisplayName("서비스 오류 → 403·400·413·429·503·504 와 code 가 그대로 내려간다")
    void errorStatuses() throws Exception {
        expect(ErrorCode.MEDICATION_NOT_AUTHORIZED, 403);
        expect(ErrorCode.MEDICATION_OCR_INVALID_IMAGE, 400);
        expect(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE, 413);
        expect(ErrorCode.MEDICATION_OCR_LIMIT_EXCEEDED, 429);
        expect(ErrorCode.MEDICATION_OCR_UNAVAILABLE, 503);
        expect(ErrorCode.MEDICATION_OCR_TIMEOUT, 504);
    }

    private void expect(ErrorCode code, int httpStatus) throws Exception {
        doThrow(new CustomException(code)).when(medicationOcrService).read(any(), any(), any());
        mockMvc.perform(multipart(URL).file(IMAGE).with(user("GD0001").roles("GUARDIAN")))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code.name()));
    }
}
