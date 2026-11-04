package kr.silverbridge.main.domain.chat.controller;

import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/** 챗봇 음성 인식 경로의 서블릿 업로드 상한 초과 → 413 음성 문구 (2026-11-04 점검 L-1). */
class ChatSttUploadLimitTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/ward/chat/stt", "/api/guardian/chat/stt"})
    @DisplayName("전역 multipart 상한 초과: 음성 인식 경로는 413 CHAT_STT_AUDIO_TOO_LARGE")
    void maxUploadSizeOnSttPathIs413(String uri) {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(10L * 1024 * 1024), new MockHttpServletRequest("POST", uri));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.CHAT_STT_AUDIO_TOO_LARGE.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ward/chat", "/api/ward/chat/stt/x", "/api/admin/chat/stt"})
    @DisplayName("비슷한 다른 경로는 음성 문구로 바뀌지 않는다")
    void otherPathsKeepDefault(String uri) {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(10L * 1024 * 1024), new MockHttpServletRequest("POST", uri));

        assertThat(response.getBody().getCode()).isNotEqualTo(ErrorCode.CHAT_STT_AUDIO_TOO_LARGE.name());
    }
}
