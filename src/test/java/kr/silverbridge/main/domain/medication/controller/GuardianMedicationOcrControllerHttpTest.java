package kr.silverbridge.main.domain.medication.controller;

import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.domain.medication.service.MedicationOcrService;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import kr.silverbridge.main.global.response.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 약 봉투 OCR 응답의 실제 HTTP 직렬화(Boot 4 웹 변환기 = Jackson 3). 반 알(0.5)·시간대 코드·null 칸이 그대로 나가고
 * reason·성분 같은 칸은 응답에 없다. 전역 multipart 상한 초과도 OCR 경로에서는 413이다.
 */
@ExtendWith(MockitoExtension.class)
class GuardianMedicationOcrControllerHttpTest {

    private static final String URL = "/api/guardian/ward/WD0001/medication/ocr";

    @Mock private MedicationOcrService medicationOcrService;

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
        return MockMvcBuilders.standaloneSetup(new GuardianMedicationOcrController(medicationOcrService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("초안이 JSON 으로 그대로 나가고, 토큰 ID·경로 wardId·image 파트가 서비스로 간다")
    void serializesDraft() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        when(medicationOcrService.read(eq("GD0001"), eq("WD0001"), any())).thenReturn(new MedicationOcrResponse(true, List.of(
                new MedicationOcrResponse.Drug("슈다페드정", 0.5, 2, 5,
                        List.of(MedicationTimeSlot.MORNING, MedicationTimeSlot.DINNER), "식후 30분", "high"),
                new MedicationOcrResponse.Drug("펜쿨리어맥스정", null, null, null, List.of(), null, "low"))));

        mvc().perform(multipart(URL).file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readable").value(true))
                .andExpect(jsonPath("$.data.drugs[0].name").value("슈다페드정"))
                .andExpect(jsonPath("$.data.drugs[0].perDose").value(0.5))
                .andExpect(jsonPath("$.data.drugs[0].timesPerDay").value(2))
                .andExpect(jsonPath("$.data.drugs[0].days").value(5))
                .andExpect(jsonPath("$.data.drugs[0].slots[0]").value("MORNING"))
                .andExpect(jsonPath("$.data.drugs[0].slots[1]").value("DINNER"))
                .andExpect(jsonPath("$.data.drugs[0].timing").value("식후 30분"))
                .andExpect(jsonPath("$.data.drugs[0].confidence").value("high"))
                .andExpect(jsonPath("$.data.drugs[1].slots").isEmpty())
                .andExpect(jsonPath("$.data.reason").doesNotExist())
                .andExpect(jsonPath("$.data.drugs[0].ingredient").doesNotExist());
    }

    @Test
    @DisplayName("전역 multipart 상한 초과: OCR 경로는 413 MEDICATION_OCR_IMAGE_TOO_LARGE, 그 밖(프로필)은 기존 400 FILE_TOO_LARGE")
    void maxUploadSizeOnOcrPathIs413() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(10L * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> ocr = handler.handleMaxUploadSizeExceeded(e, new MockHttpServletRequest("POST", URL));
        assertThat(ocr.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(ocr.getBody().getCode()).isEqualTo(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE.name());

        ResponseEntity<ApiResponse<Void>> profile =
                handler.handleMaxUploadSizeExceeded(e, new MockHttpServletRequest("PATCH", "/api/user/me/profile-image"));
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(profile.getBody().getCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE.name());
    }
}
