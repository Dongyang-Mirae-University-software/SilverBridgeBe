package kr.silverbridge.main.global.client;

import kr.silverbridge.main.global.exception.CustomException;
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

    @Test
    @DisplayName("접수 거부는 그대로 REJECTED")
    void 접수거부_REJECTED() {
        assertThat(senderReturning(SmsSender.Outcome.REJECTED, 0).trySend("01012345678", "본문"))
                .isEqualTo(SmsSender.Outcome.REJECTED);
    }
}
