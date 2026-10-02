package kr.silverbridge.main.domain.notification.service;

import com.solapi.sdk.message.model.Message;
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
}
