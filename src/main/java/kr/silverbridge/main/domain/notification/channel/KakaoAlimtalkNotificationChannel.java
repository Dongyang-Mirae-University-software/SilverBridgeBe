package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.config.AlimtalkProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.service.AlimtalkSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 카카오 알림톡 채널 구현체(Solapi 발신 프로필 경유). 디스패처가 자동 수집한다(전략 패턴).
 *
 * <p><b>카카오톡 채팅으로 도착하는 유일한 현실적 경로</b>다 — 수신자를 전화번호로 식별하므로 카카오 로그인·친구
 * 추가가 필요 없다(카카오톡 메시지 API는 발신자·수신자가 카카오 친구여야 해 시니어 대상 서비스에서 쓸 수 없다).
 * 참고로 "카카오 푸시 알림"은 카카오톡이 아니라 <b>우리 앱 푸시(FCM 경유)</b>라 기존 FCM 발송과 도착지가 같다.</p>
 *
 * <p><b>알림톡은 자유 문구를 보낼 수 없다</b> — 사전 심사에서 승인된 템플릿 문구만 나가고, 우리는 {@code #{변수}}만
 * 채운다. 따라서 알림 종류마다 승인 템플릿이 하나씩 있어야 하며, 매핑은 {@link AlimtalkProperties#getTemplates()}
 * ({@code notification.alimtalk.templates.<TYPE>})에 둔다.</p>
 *
 * <p><b>템플릿이 없으면 발송하지 않는다</b>(스킵 후 {@code false}). 승인 전에 다른 용도의 템플릿으로 억지로 보내면
 * 문구가 어긋나 카카오 채널 제재 대상이 된다. 즉 이 빈이 있어도 미승인 종류는 조용히 스킵되어 기존 동작이 유지된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoAlimtalkNotificationChannel implements NotificationChannel {

    private final AlimtalkSender alimtalkSender;
    private final AlimtalkProperties properties;

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.KAKAO_ALIMTALK;
    }

    @Override
    public boolean send(NotificationType type, NotificationRecipient recipient, NotificationContent content) {
        if (recipient.phone() == null || recipient.phone().isBlank()) {
            log.warn("알림톡 건너뜀(전화번호 없음): userId={}", recipient.userId());
            return false;
        }

        // 템플릿은 발송 종류(type)로 고른다 — data["type"]은 클라이언트가 파싱하는 FE 계약 값이라
        // 발송 라우팅을 거기에 묶으면 FE 계약이 바뀔 때 엉뚱한 템플릿이 선택될 수 있다.
        AlimtalkProperties.Template template = properties.templateFor(type != null ? type.name() : null);
        if (template == null) {
            // 승인된 템플릿이 없는 알림 종류 — 발송 수단이 없으므로 스킵(설정을 켜도 아무 일도 일어나지 않는다).
            // 승인 문구와 수신자가 어긋나는 발송을 막는 지점이기도 하다(예: 보호자용 문구를 피보호자에게).
            log.debug("알림톡 템플릿 미설정 — 건너뜀: userId={}, type={}", recipient.userId(), type);
            return false;
        }

        Map<String, String> variables = template.bindVariables(content);
        return alimtalkSender.send(recipient.phone(), template.getTemplateId(), variables);
    }
}
