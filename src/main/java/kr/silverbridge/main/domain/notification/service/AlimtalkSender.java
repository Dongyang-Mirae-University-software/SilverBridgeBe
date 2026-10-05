package kr.silverbridge.main.domain.notification.service;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.exception.SolapiEmptyResponseException;
import com.solapi.sdk.message.exception.SolapiMessageNotReceivedException;
import com.solapi.sdk.message.exception.SolapiUnknownException;
import com.solapi.sdk.message.model.Message;
import com.solapi.sdk.message.model.kakao.KakaoOption;
import com.solapi.sdk.message.service.DefaultMessageService;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.config.AlimtalkProperties;
import kr.silverbridge.main.global.client.SolapiCallExecutor;
import kr.silverbridge.main.global.client.SolapiFailureCodes;
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
 * <p>발송 실패는 예외를 던지지 않고 실패 결과를 돌려준다 — 알림톡은 부가 채널이라 실패가 FCM 발송이나
 * 이력 저장에 영향을 주면 안 된다(디스패처의 채널 격리와 이중 방어).</p>
 *
 * <p>SDK 호출은 {@link SolapiCallExecutor}로 시간 제한(기본 10초)을 건다(2026-10-02 QA P16) - SDK 자체 제한이 50초
 * 고정이라 이상감지 알림톡이 긴급 알림 스레드를 오래 붙드는 것을 막는다. 시간 초과는 기존 고정 코드
 * {@link ChannelFailureReason#PROVIDER_ERROR}(발송 서버 오류)로 기록한다 - 응답을 받지 못했다는 점에서 통신 오류와 같다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlimtalkSender {

    private final AlimtalkProperties properties;
    private final SolapiCallExecutor solapiCallExecutor;

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
     * @return 발송 요청이 접수되면 성공, 실패하면 사유(접수 거부 / 통신 오류)
     */
    public ChannelResult send(String phone, String templateId, Map<String, String> variables) {
        KakaoOption kakaoOption = new KakaoOption();
        kakaoOption.setPfId(properties.getPfId());
        kakaoOption.setTemplateId(templateId);
        kakaoOption.setVariables(new HashMap<>(variables));
        kakaoOption.setDisableSms(true);   // SMS 대체발송 금지 — 문자는 사용자가 켠 경우에만 나간다

        Message message = new Message();
        message.setFrom(senderPhone);
        message.setTo(phone);
        message.setKakaoOptions(kakaoOption);   // text는 채우지 않는다(알림톡 규칙)

        // 시간 초과·풀 포화는 빈 값 - 응답을 못 받았으므로 통신 오류로 기록한다.
        return solapiCallExecutor.call("알림톡", () -> sendNow(message, templateId))
                .orElseGet(() -> ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR));
    }

    /** SDK 호출 본체. {@link SolapiCallExecutor} 스레드에서 실행된다(테스트에서 느린 호출로 대체). */
    // 로그에는 템플릿 ID·예외 클래스명·발송사 상태 코드만 남긴다(QA XCUT-G11 후속) - 실패 목록 문자열에는 수신·발신 번호가,
    // 예외 메시지에는 응답 원문이 들어 있다(알림 이력 불변 규칙 ② 예외 원문 금지).
    ChannelResult sendNow(Message message, String templateId) {
        try {
            deliver(message);
            return ChannelResult.delivered();
        } catch (SolapiMessageNotReceivedException e) {
            log.error("[ALIMTALK-SEND-REJECTED] 알림톡 접수 거부: templateId={}, error={}, statusCodes={}",
                    templateId, e.getClass().getSimpleName(), SolapiFailureCodes.statusCodes(e));
            return ChannelResult.failed(ChannelFailureReason.PROVIDER_REJECTED);
        } catch (SolapiEmptyResponseException | SolapiUnknownException e) {
            log.error("[ALIMTALK-SEND-ERROR] 알림톡 발송 오류: templateId={}, error={}",
                    templateId, e.getClass().getSimpleName());
            return ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR);
        }
    }

    /** SDK 전송 1회. 테스트에서 SDK 예외를 흉내 내려고 분리했다. */
    void deliver(Message message)
            throws SolapiMessageNotReceivedException, SolapiEmptyResponseException, SolapiUnknownException {
        DefaultMessageService messageService = SolapiClient.INSTANCE.createInstance(apiKey, apiSecret);
        messageService.send(message);
    }
}
