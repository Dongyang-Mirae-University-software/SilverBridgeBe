package kr.silverbridge.main.domain.notification.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.model.kakao.KakaoOption;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.domain.notification.config.AlimtalkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Solapi SDK 기반 카카오 알림톡 발송 컴포넌트({@code SmsSender}의 알림톡 판 — 같은 계정·SDK를 쓴다).
 *
 * <p>알림톡은 <b>{@code text}를 채우지 않는다</b> — 문구는 승인된 템플릿에서 오고 우리는 변수만 넘긴다.</p>
 *
 * <p><b>{@code disableSms=true}</b>로 Solapi의 SMS 대체발송을 끈다: 알림톡 실패를 문자로 메우면 문자를
 * 선택하지 않은 사용자에게 과금·발송이 발생해 "문자는 사용자 선택"이라는 정책(이상감지 D-2)을 뒤집는다.</p>
 *
 * <p>발송 실패는 예외를 던지지 않고 {@code false}를 돌려준다 — 알림톡은 부가 채널이라 실패가 FCM 발송이나
 * 이력 저장에 영향을 주면 안 된다(디스패처의 채널 격리와 이중 방어).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlimtalkSender {

    private final AlimtalkProperties properties;

    @Value("${solapi.api-key}")
    private String apiKey;

    @Value("${solapi.api-secret}")
    private String apiSecret;

    @Value("${solapi.sender-phone}")
    private String senderPhone;

    /**
     * 알림톡 1건 발송.
     *
     * @param phone      수신 번호(카카오톡 계정에 등록된 번호로 전달된다)
     * @param templateId 승인된 템플릿 ID
     * @param variables  {@code "#{변수}" → 값}
     * @return 발송 요청이 접수되면 true, 실패하면 false
     */
    public boolean send(String phone, String templateId, Map<String, String> variables) {
        KakaoOption kakaoOption = new KakaoOption();
        kakaoOption.setPfId(properties.getPfId());
        kakaoOption.setTemplateId(templateId);
        kakaoOption.setVariables(new HashMap<>(variables));
        kakaoOption.setDisableSms(true);   // SMS 대체발송 금지 — 문자는 사용자가 켠 경우에만 나간다

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setKakaoOptions(kakaoOption);   // text는 채우지 않는다(알림톡 규칙)

        DefaultMessageService messageService = SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);
        try {
            messageService.send(message);
            return true;
        } catch (SolapiMessageNotReceivedException e) {
            log.error("알림톡 발송 실패: templateId={}, failed={}", templateId, e.getFailedMessageList());
            return false;
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("알림톡 오류: templateId={}, error={}", templateId, e.getMessage());
            return false;
        }
    }
}
