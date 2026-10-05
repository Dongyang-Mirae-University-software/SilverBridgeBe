package kr.silverbridge.main.global.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.FailedMessage;
import com.solapi.sdk.message.model.Message;
import kr.silverbridge.main.global.exception.CustomException;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SmsSender 시간 제한(QA P16) - SDK 호출 본체를 느린 가짜로 바꿔, 알림 채널(trySend)과 인증번호(send) 두 경로가
 * 제한 시간 안에 기존 실패 처리로 돌아오는지 확인한다.
 */
@DisplayName("SmsSender - Solapi 호출 시간 제한")
class SmsSenderTest {

    private final SolapiCallExecutor executor = new SolapiCallExecutor(Duration.ofMillis(300));

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private SmsSender senderReturning(SmsSender.Outcome outcome, long delayMillis) {
        return new SmsSender(executor) {
            @Override
            Outcome sendNow(String phone, String text) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return outcome;
            }
        };
    }

    @Test
    @DisplayName("정상 접수면 SENT, send는 예외 없이 끝난다")
    void 정상_발송() {
        SmsSender sender = senderReturning(SmsSender.Outcome.SENT, 0);

        assertThat(sender.trySend("01012345678", "본문")).isEqualTo(SmsSender.Outcome.SENT);
        sender.send("01012345678", "본문");
    }

    @Test
    @DisplayName("알림 채널 경로: 시간 초과면 제한 시간 안에 ERROR(통신 오류)로 돌아온다")
    void trySend_시간초과_ERROR() {
        SmsSender sender = senderReturning(SmsSender.Outcome.SENT, 10_000);

        long start = System.nanoTime();
        SmsSender.Outcome outcome = sender.trySend("01012345678", "본문");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(outcome).isEqualTo(SmsSender.Outcome.ERROR);
        assertThat(elapsedMillis).isLessThan(3_000);
    }

    @Test
    @DisplayName("인증번호 경로: 시간 초과면 기존 발송 실패와 같은 SMS_SEND_FAILED를 던진다(호출자가 한도 환불)")
    void send_시간초과_SMS_SEND_FAILED() {
        SmsSender sender = senderReturning(SmsSender.Outcome.SENT, 10_000);

        assertThatThrownBy(() -> sender.send("01012345678", "본문"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SMS_SEND_FAILED);
    }

    // ── 실패 로그에 예외 원문·번호를 남기지 않는다 (QA XCUT-G11 후속, 알림 이력 불변 규칙 ②) ──

    private SmsSender senderThrowing(Exception toThrow) {
        return new SmsSender(executor) {
            @Override
            void deliver(Message message) throws SolapiMessageNotReceivedException, SolapiEmptyResponseException,
                    SolapiUnknownException {
                if (toThrow instanceof SolapiMessageNotReceivedException e) throw e;
                if (toThrow instanceof SolapiEmptyResponseException e) throw e;
                throw (SolapiUnknownException) toThrow;
            }
        };
    }

    @Test
    @DisplayName("접수 거부 - REJECTED, 로그에는 클래스명·상태 코드만(번호·사유 원문 없음, 형식이 이상한 코드는 버림)")
    void 접수거부_로그_마스킹() {
        SolapiMessageNotReceivedException rejected = new SolapiMessageNotReceivedException("원문 01099998888 거부");
        rejected.setFailedMessageList(List.of(
                new FailedMessage("01099998888", "0212345678", "SMS", "82", "M-1", "1062", "번호 오류 01099998888",
                        "acc-1", Map.of()),
                new FailedMessage("01099998888", "0212345678", "SMS", "82", "M-2", "bad code 01099998888", "x",
                        "acc-1", Map.of())));

        try (LogCapture logs = LogCapture.of(SmsSender.class)) {
            assertThat(senderThrowing(rejected).trySend("01099998888", "본문")).isEqualTo(SmsSender.Outcome.REJECTED);

            assertThat(logs.messages()).anySatisfy(m -> assertThat(m)
                    .startsWith("[SMS-SEND-REJECTED]").contains("SolapiMessageNotReceivedException", "1062"));
            assertThat(logs.messages()).allSatisfy(m -> assertThat(m)
                    .doesNotContain("01099998888", "0212345678", "원문", "번호 오류", "bad code"));
        }
    }

    @Test
    @DisplayName("빈 응답·알 수 없는 오류 - ERROR, 로그에 예외 메시지(응답 원문)를 싣지 않는다")
    void 통신오류_로그_마스킹() {
        try (LogCapture logs = LogCapture.of(SmsSender.class)) {
            assertThat(senderThrowing(new SolapiEmptyResponseException("empty 01099998888")).trySend("01099998888", "본문"))
                    .isEqualTo(SmsSender.Outcome.ERROR);
            assertThat(senderThrowing(new SolapiUnknownException("raw apiSecret=abc")).trySend("01099998888", "본문"))
                    .isEqualTo(SmsSender.Outcome.ERROR);

            assertThat(logs.messages()).filteredOn(m -> m.startsWith("[SMS-SEND-ERROR]")).hasSize(2);
            assertThat(logs.messages()).allSatisfy(m -> assertThat(m)
                    .doesNotContain("01099998888", "empty", "apiSecret"));
        }
    }

    static final class LogCapture implements AutoCloseable {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type);
            appender.start();
            logger.addAppender(appender);
        }

        static LogCapture of(Class<?> type) {
            return new LogCapture(type);
        }

        List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("접수 거부는 그대로 REJECTED")
    void 접수거부_REJECTED() {
        assertThat(senderReturning(SmsSender.Outcome.REJECTED, 0).trySend("01012345678", "본문"))
                .isEqualTo(SmsSender.Outcome.REJECTED);
    }
}
