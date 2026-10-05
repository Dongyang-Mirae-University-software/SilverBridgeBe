package kr.silverbridge.main.domain.notification.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.FailedMessage;
import com.solapi.sdk.message.model.Message;
import org.slf4j.LoggerFactory;

import java.util.List;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.config.AlimtalkProperties;
import kr.silverbridge.main.global.client.SolapiCallExecutor;
import kr.silverbridge.main.global.client.SolapiCallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlimtalkSender 시간 제한(QA P16) - 느린 SDK 호출이 긴급 알림 스레드를 붙들지 않고, 결과에는 고정 코드만 실리는지.
 */
@DisplayName("AlimtalkSender - Solapi 호출 시간 제한")
class AlimtalkSenderTest {

    private final SolapiCallExecutor executor = newExecutor();

    private static SolapiCallExecutor newExecutor() {
        SolapiCallProperties properties = new SolapiCallProperties();
        properties.setCallTimeoutSeconds(1);
        return new SolapiCallExecutor(properties);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(executor, "shutdown");
    }

    private AlimtalkSender senderReturning(ChannelResult result, long delayMillis) {
        AlimtalkProperties properties = new AlimtalkProperties();
        properties.setPfId("KA01PF-test");
        return new AlimtalkSender(properties, executor) {
            @Override
            ChannelResult sendNow(Message message, String templateId) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        };
    }

    @Test
    @DisplayName("정상 접수면 전달 결과를 그대로 돌려준다")
    void 정상_발송() {
        ChannelResult result = senderReturning(ChannelResult.delivered(), 0)
                .send("01012345678", "KA01TP-test", Map.of("wardName", "김순자"));

        assertThat(result.isDelivered()).isTrue();
    }

    @Test
    @DisplayName("시간 초과면 제한 시간 안에 고정 코드 PROVIDER_ERROR 실패로 돌아온다(예외 원문·번호 없음)")
    void 시간초과_PROVIDER_ERROR() {
        AlimtalkSender sender = senderReturning(ChannelResult.delivered(), 10_000);

        long start = System.nanoTime();
        ChannelResult result = sender.send("01012345678", "KA01TP-test", Map.of("wardName", "김순자"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        assertThat(result.toString()).doesNotContain("01012345678");
        assertThat(elapsedMillis).isLessThan(4_000);
    }

    // ── 실패 로그에 예외 원문·번호를 남기지 않는다 (QA XCUT-G11 후속, 알림 이력 불변 규칙 ②) ──

    private AlimtalkSender senderThrowing(Exception toThrow) {
        AlimtalkProperties properties = new AlimtalkProperties();
        properties.setPfId("KA01PF-test");
        return new AlimtalkSender(properties, executor) {
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
    @DisplayName("접수 거부 - PROVIDER_REJECTED, 로그에는 클래스명·상태 코드만(번호·사유 원문 없음)")
    void 접수거부_로그_마스킹() {
        SolapiMessageNotReceivedException rejected = new SolapiMessageNotReceivedException("원문 01099998888 거부");
        rejected.setFailedMessageList(List.of(new FailedMessage("01099998888", "0212345678", "ATA", "82",
                "M-1", "3059", "변수 불일치 01099998888", "acc-1", Map.of())));

        try (LogCapture logs = LogCapture.of(AlimtalkSender.class)) {
            ChannelResult result = senderThrowing(rejected).send("01099998888", "KA01TP-test", Map.of());

            assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_REJECTED);
            assertThat(logs.messages()).anySatisfy(m -> assertThat(m)
                    .startsWith("[ALIMTALK-SEND-REJECTED]")
                    .contains("SolapiMessageNotReceivedException", "3059", "KA01TP-test"));
            assertThat(logs.messages()).allSatisfy(m -> assertThat(m)
                    .doesNotContain("01099998888", "0212345678", "원문", "변수 불일치"));
        }
    }

    @Test
    @DisplayName("통신 오류 - PROVIDER_ERROR, 로그에 예외 메시지(응답 원문)를 싣지 않는다")
    void 통신오류_로그_마스킹() {
        try (LogCapture logs = LogCapture.of(AlimtalkSender.class)) {
            ChannelResult result = senderThrowing(new SolapiUnknownException("raw body 01099998888 apiKey=xyz"))
                    .send("01099998888", "KA01TP-test", Map.of());

            assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
            assertThat(logs.messages()).anySatisfy(m -> assertThat(m)
                    .startsWith("[ALIMTALK-SEND-ERROR]").contains("SolapiUnknownException"));
            assertThat(logs.messages()).allSatisfy(m -> assertThat(m)
                    .doesNotContain("01099998888", "raw body", "apiKey"));
        }
    }

    /** 로그 수집 - SDK 스레드(SolapiCallExecutor)에서 찍혀도 같은 로거라 잡힌다. */
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
}
