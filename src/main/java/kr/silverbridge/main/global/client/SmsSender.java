package kr.silverbridge.main.global.client;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Solapi SDK 기반 SMS 발송 전용 컴포넌트
 * 발송 실패는 {@link ErrorCode#SMS_SEND_FAILED}로 통일 처리
 *
 * <p>인증번호 흐름은 {@link #send}(실패 = 예외)를, 알림 채널은 {@link #trySend}(실패 = 결과값)를 쓴다.
 * 알림 채널은 관리자 알림 이력에 "접수 거부"와 "통신 오류"를 나눠 남겨야 해서 예외 하나로는 부족하다.</p>
 *
 * <p>SDK 호출은 {@link SolapiCallExecutor}로 시간 제한(기본 10초)을 건다(2026-10-02 QA P16) - SDK 자체 제한이
 * 50초로 고정이라 SOS 문자 폴백이 긴급 알림 스레드를 오래 붙드는 것을 막는다. 시간 초과는 {@link Outcome#ERROR}
 * (인증번호 흐름에서는 {@link ErrorCode#SMS_SEND_FAILED})로 기존 통신 오류와 똑같이 처리된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsSender {

    private final SolapiCallExecutor solapiCallExecutor;

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender-phone}")
    private String senderPhone;

    /** Solapi 발송 결과. */
    public enum Outcome {
        SENT,
        /** 발송사가 접수를 거부했다. */
        REJECTED,
        /** 통신 오류·빈 응답. */
        ERROR
    }

    public void send(String phone, String text) {
        if (trySend(phone, text) != Outcome.SENT) {
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    /** 예외 대신 결과를 돌려주는 발송. 실패 원인은 여기서 로그로 남긴다. */
    public Outcome trySend(String phone, String text) {
        // 시간 초과·풀 포화는 빈 값 - 응답을 못 받았으므로 통신 오류와 같다.
        return solapiCallExecutor.call("SMS", () -> sendNow(phone, text)).orElse(Outcome.ERROR);
    }

    /** SDK 호출 본체. {@link SolapiCallExecutor} 스레드에서 실행된다(테스트에서 느린 호출로 대체). */
    // 로그에는 예외 클래스명·발송사 상태 코드만 남긴다(QA XCUT-G11 후속) - 실패 목록 문자열에는 수신·발신 번호가,
    // 예외 메시지에는 응답 원문이 들어 있다(알림 이력 불변 규칙 ② 예외 원문 금지).
    Outcome sendNow(String phone, String text) {
        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText(text);

        try {
            deliver(message);
            return Outcome.SENT;
        } catch (SolapiMessageNotReceivedException e) {
            log.error("[SMS-SEND-REJECTED] SMS 접수 거부: error={} statusCodes={}",
                    e.getClass().getSimpleName(), SolapiFailureCodes.statusCodes(e));
            return Outcome.REJECTED;
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("[SMS-SEND-ERROR] SMS 발송 오류: error={}", e.getClass().getSimpleName());
            return Outcome.ERROR;
        }
    }

    /** SDK 전송 1회. 테스트에서 SDK 예외를 흉내 내려고 분리했다. */
    void deliver(Message message)
            throws SolapiMessageNotReceivedException, SolapiEmptyResponseException, SolapiUnknownException {
        DefaultMessageService messageService = SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);
        messageService.send(message);
    }
}
