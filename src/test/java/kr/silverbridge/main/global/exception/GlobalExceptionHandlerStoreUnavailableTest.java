package kr.silverbridge.main.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.silverbridge.main.global.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

/**
 * 저장소(Redis) 일시 장애는 서버 결함이 아니므로 500 이 아니라 503 으로 답한다.
 * 로그인 잠금·비밀번호 확인 잠금·인증번호 카운터처럼 장애 중에는 풀어주지 않는(fail-closed) 곳이
 * Redis 예외를 그대로 던지기 때문에, 공통 핸들러에서 한 번에 503 으로 바꾼다.
 */
class GlobalExceptionHandlerStoreUnavailableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static Stream<Exception> storeFailures() {
        return Stream.of(
                new RedisConnectionFailureException("Unable to connect to Redis"),
                new RedisSystemException("OOM command not allowed", new RuntimeException("OOM")),
                new QueryTimeoutException("Redis command timed out"));
    }

    @ParameterizedTest
    @MethodSource("storeFailures")
    @DisplayName("Redis 연결 실패·타임아웃·명령 오류 → 503 SERVICE_UNAVAILABLE (500 아님)")
    void storeFailure_is_503(Exception failure) {
        ResponseEntity<ApiResponse<Void>> res = handler.handleStoreUnavailable(failure);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.name());
    }

    @Test
    @DisplayName("응답 본문에 내부 예외 메시지(호스트·키 등)를 싣지 않는다")
    void response_does_not_leak_internal_message() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleStoreUnavailable(
                new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getMessage()).doesNotContain("redis.internal").doesNotContain("6379");
    }

    @Test
    @DisplayName("컨트롤러까지 올라온 Redis 예외는 HTTP 503, 그 외 예외는 그대로 500")
    void http_flow_503_for_store_500_for_others() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(handler)
                .build();

        mvc.perform(get("/store-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        mvc.perform(get("/real-bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/store-down")
        String storeDown() {
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        }

        @GetMapping("/real-bug")
        String realBug() {
            throw new IllegalStateException("진짜 서버 결함");
        }
    }
}
