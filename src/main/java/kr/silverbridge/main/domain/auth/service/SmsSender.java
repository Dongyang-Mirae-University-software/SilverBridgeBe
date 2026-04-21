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

    public void send(String phone, String text) {
        DefaultMessageService messageService =
                SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setText(text);

        try {
            messageService.send(message);
        } catch (SolapiMessageNotReceivedException e) {
            log.error("SMS 발송 실패: {}", e.getFailedMessageList());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("SMS 오류: {}", e.getMessage());
            throw new CustomException(ErrorCode.SMS_SEND_FAILED);
        }
    }
}
