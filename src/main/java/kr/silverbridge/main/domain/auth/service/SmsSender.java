package kr.silverbridge.main.domain.auth.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Solapi SDK 기반 SMS 발송 전용 컴포넌트
 * 발송 실패는 {@link ErrorCode#SMS_SEND_FAILED}로 통일 처리
 *
 * <p>인증번호 흐름은 {@link #send}(실패 = 예외)를, 알림 채널은 {@link #trySend}(실패 = 결과값)를 쓴다.
 * 알림 채널은 관리자 알림 이력에 "접수 거부"와 "통신 오류"를 나눠 남겨야 해서 예외 하나로는 부족하다.</p>
 */
@Slf4j
@Component
public class SmsSender {

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
        DefaultMessageService messageService =
                SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText(text);

        try {
            messageService.send(message);
            return Outcome.SENT;
        } catch (SolapiMessageNotReceivedException e) {
            log.error("SMS 발송 실패: {}", e.getFailedMessageList());
            return Outcome.REJECTED;
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("SMS 오류: {}", e.getMessage());
            return Outcome.ERROR;
        }
    }
}
