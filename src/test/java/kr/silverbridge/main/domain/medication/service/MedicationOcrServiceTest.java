package kr.silverbridge.main.domain.medication.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient.ImageFormat;
import kr.silverbridge.main.domain.medication.config.MedicationOcrProperties;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 약 봉투 OCR 서비스 - 순서(킬 스위치 → 속도 제한 → 인가 → 크기·형식 → 동시 자리 → AI)와 각 단계의 거절을 고정한다.
 * PENDING·해제 연결은 {@code isActiveConnection=false}로 같은 403 경로를 탄다(인가는 ACTIVE 연결만).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class MedicationOcrServiceTest {

    private static final String GUARDIAN = "GD0001";
    private static final String WARD = "WD0001";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 9};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private AiMedicationOcrClient aiClient;
    @Mock private RateLimitService rateLimitService;
    @Mock private ConnectionService connectionService;

    private MedicationOcrProperties properties;
    private MedicationOcrSlots slots;
    private MedicationOcrService service;

    @BeforeEach
    void setUp() {
        properties = new MedicationOcrProperties();
        slots = new MedicationOcrSlots(properties);
        service = new MedicationOcrService(aiClient, slots, rateLimitService, connectionService, properties);
    }

    private static MockMultipartFile image(byte[] bytes) {
        return new MockMultipartFile("image", "홍길동_처방전.jpg", "image/jpeg", bytes);
    }

    private ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("CustomException 이 나야 한다");
    }

    @Test
    @DisplayName("성공: 연결 확인 후 AI 결과를 허용 목록으로 걸러 돌려주고, 로그에는 약 이름·사진 파일 이름이 없다")
    void success(CapturedOutput output) throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiClient.read(any(), any())).thenReturn(MAPPER.readTree(
                "{\"readable\":true,\"reason\":\"r\",\"drugs\":[{\"name\":\"타이레놀정\",\"ingredient\":\"x\",\"confidence\":\"high\"}]}"));

        MedicationOcrResponse r = service.read(GUARDIAN, WARD, image(JPEG));

        assertThat(r.readable()).isTrue();
        assertThat(r.drugs()).extracting(MedicationOcrResponse.Drug::name).containsExactly("타이레놀정");
        verify(aiClient).read(JPEG, ImageFormat.JPEG);
        InOrder order = inOrder(rateLimitService, connectionService, aiClient);
        order.verify(rateLimitService).check("medication-ocr", GUARDIAN, 10, 60);
        order.verify(connectionService).isActiveConnection(GUARDIAN, WARD);
        order.verify(aiClient).read(any(), any());
        assertThat(output).contains("[MED-OCR] 완료", "drugCount=1")
                .doesNotContain("타이레놀정", "홍길동");
        assertThat(slots.activeUsers()).isZero();
    }

    @Test
    @DisplayName("킬 스위치 off → 503, 속도 제한·인가·AI 모두 건드리지 않는다")
    void killSwitch() {
        properties.setEnabled(false);

        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        verifyNoInteractions(rateLimitService, connectionService, aiClient);
    }

    @Test
    @DisplayName("속도 제한 초과 → 429, 인가·AI 전에 막는다")
    void rateLimited() {
        doThrow(new TooManyRequestsException(30)).when(rateLimitService).check(anyString(), anyString(), anyInt(), anyInt());

        assertThatThrownBy(() -> service.read(GUARDIAN, WARD, image(JPEG))).isInstanceOf(TooManyRequestsException.class);
        verifyNoInteractions(connectionService, aiClient);
    }

    @Test
    @DisplayName("ACTIVE 연결이 아니면(미연결·PENDING·해제) 403 + [IDOR-ATTEMPT], 사진 검사·AI 호출 전에 막는다")
    void notConnected(CapturedOutput output) {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

        // 사진이 아닌 파일이어도 403이 먼저다 - 연결 없는 보호자에게 형식 판정 결과를 주지 않는다
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image("text".getBytes(StandardCharsets.UTF_8)))))
                .isEqualTo(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        verifyNoInteractions(aiClient);
        assertThat(output).contains("[IDOR-ATTEMPT]", "guardianId=" + GUARDIAN, "wardId=" + WARD, "action=ocr");
    }

    @Test
    @DisplayName("8MB 초과 → 413, 비었거나 파트 없음·사진 아님(gif·텍스트) → 400 - AI는 부르지 않는다")
    void sizeAndFormat() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        properties.setMaxImageBytes(8);

        byte[] nine = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6};
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(nine)))).isEqualTo(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE);
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(new byte[0])))).isEqualTo(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, null))).isEqualTo(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image("GIF89a".getBytes(StandardCharsets.US_ASCII)))))
                .isEqualTo(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        verify(aiClient, never()).read(any(), any());
    }

    @Test
    @DisplayName("기본 크기 상한은 8MB(경계 포함 허용)")
    void defaultLimitIs8MB() {
        assertThat(properties.getMaxImageBytes()).isEqualTo(8L * 1024 * 1024);
    }

    @Test
    @DisplayName("같은 보호자가 처리 중이면 429(동시 1인 1), 끝나면 자리가 돌아온다")
    void perUserConcurrency() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiClient.read(any(), any())).thenReturn(MAPPER.readTree("{\"readable\":false,\"drugs\":[]}"));

        try (MedicationOcrSlots.Slot held = slots.acquire(GUARDIAN)) {
            assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_LIMIT_EXCEEDED);
        }
        assertThat(service.read(GUARDIAN, WARD, image(JPEG)).readable()).isFalse();
    }

    @Test
    @DisplayName("서버 전체 동시 상한(5)을 넘으면 다른 보호자도 429")
    void globalConcurrency() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        for (int i = 0; i < 5; i++) {
            slots.acquire("OTHER" + i);
        }

        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_LIMIT_EXCEEDED);
        verifyNoInteractions(aiClient);
    }

    @Test
    @DisplayName("AI 오류(503·504)는 그대로 전달하고 자리를 돌려준다, 형식 이상 응답은 503")
    void aiFailures() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);

        when(aiClient.read(any(), any())).thenThrow(new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE));
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        assertThat(slots.activeUsers()).isZero();

        org.mockito.Mockito.reset(aiClient);
        when(aiClient.read(any(), any())).thenThrow(new CustomException(ErrorCode.MEDICATION_OCR_TIMEOUT));
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_TIMEOUT);

        org.mockito.Mockito.reset(aiClient);
        when(aiClient.read(any(), any())).thenReturn(MAPPER.readTree("{\"drugs\":[]}"));
        assertThat(codeOf(() -> service.read(GUARDIAN, WARD, image(JPEG)))).isEqualTo(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        assertThat(slots.activeUsers()).isZero();
    }
}
