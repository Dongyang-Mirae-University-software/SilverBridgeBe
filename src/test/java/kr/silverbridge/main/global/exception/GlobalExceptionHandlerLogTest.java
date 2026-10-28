package kr.silverbridge.main.global.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.silverbridge.main.global.response.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공용 예외 처리기는 예외 원문을 로그에 남기지 않는다(2026-10-28, AI 챗 집계 점검 L-1 후속).
 * 응답은 그대로이고 로그만 클래스명·이름·발생 위치로 바뀐 것을 고정한다.
 */
class GlobalExceptionHandlerLogTest {

    private static final String SECRET = "SECRETKEY123";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("IAE - 헤더 값 오류에 실린 키 원문은 로그에 없고, 클래스명과 우리 코드의 발생 위치만 남는다. 응답은 400 INVALID_INPUT 그대로")
    void illegalArgumentDropsMessage() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleIllegalArgument(
                new IllegalArgumentException("Illegal character(s) in message header value: " + SECRET + "\r\n"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("INVALID_INPUT");
        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line).doesNotContain(SECRET).doesNotContain("header value");
            assertThat(line).contains("type=IllegalArgumentException").contains("at=GlobalExceptionHandlerLogTest.java:");
        });
    }

    @Test
    @DisplayName("NumberFormatException(IAE 하위)의 사용자 입력 값도 로그에 남지 않는다")
    void numberFormatDropsInput() {
        IllegalArgumentException thrown;
        try {
            Long.parseLong("010-1234-5678");
            throw new AssertionError("파싱되면 안 된다");
        } catch (NumberFormatException e) {
            thrown = e;
        }

        handler.handleIllegalArgument(thrown);

        assertThat(logs()).singleElement().satisfies(line -> {
            assertThat(line).doesNotContain("010-1234-5678");
            assertThat(line).contains("type=NumberFormatException");
        });
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type - 요청자가 넣은 값(줄바꿈으로 만든 가짜 줄)은 로그에 없다, 응답 415 그대로")
    void mediaTypeDropsHeaderValue() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("Content-Type 'text/x\r\nFAKE-LOG-LINE' is not supported"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(logs()).singleElement().satisfies(line -> assertThat(line).doesNotContain("FAKE-LOG-LINE"));
    }

    @Test
    @DisplayName("CustomException·낙관적 잠금 - 오류 코드 이름·엔티티 클래스명만 남는다(엔티티 ID 없음)")
    void customAndOptimisticLock() {
        handler.handleCustomException(new CustomException(ErrorCode.USER_NOT_FOUND));
        handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException("kr.silverbridge.Connection", "ID-9876"));

        assertThat(logs()).containsExactly(
                "CustomException: code=USER_NOT_FOUND",
                "OptimisticLockingFailure: entity=kr.silverbridge.Connection");
    }

    @Test
    @DisplayName("발생 위치 - 우리 패키지 프레임이 없으면 맨 위 프레임, 스택이 없으면 '?'")
    void originFallbacks() {
        IllegalArgumentException noFrames = new IllegalArgumentException("x");
        noFrames.setStackTrace(new StackTraceElement[0]);
        IllegalArgumentException jdkOnly = new IllegalArgumentException("x");
        jdkOnly.setStackTrace(new StackTraceElement[] {new StackTraceElement("java.net.Foo", "m", "Foo.java", 12)});

        assertThat(GlobalExceptionHandler.origin(noFrames)).isEqualTo("?");
        assertThat(GlobalExceptionHandler.origin(jdkOnly)).isEqualTo("Foo.java:12");
    }
}
