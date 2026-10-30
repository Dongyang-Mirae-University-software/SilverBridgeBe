package kr.silverbridge.main.domain.notification.config;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.Set;

/**
 * FCM 메시지 구성 (application.yaml {@code notification.fcm.*}, 2026-10-30).
 *
 * <p>발송 대상·채널·전달 판정과는 무관하다 - 같은 알림을 <b>어떤 모양의 메시지로</b> 보낼지만 정한다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "notification.fcm")
public class FcmMessageProperties {

    /**
     * true면 웹 토큰(platform=WEB)에 notification 블록을 빼고 data만 보낸다.
     *
     * <p>notification 블록이 있으면 Firebase SW 라이브러리가 알림을 자동으로 띄우고 FE의
     * {@code onBackgroundMessage}가 한 번 더 띄워 <b>알림이 두 번 뜬다</b>. data 전용이면 FE 서비스 워커만 그린다.
     * ⚠️ FE 서비스 워커가 {@code data.title}·{@code data.body}로 그리도록 배포된 뒤에만 켤 것 - 먼저 켜면 제목 없는
     * 알림이 뜬다. 웹이 아닌 토큰은 이 값과 무관하게 기존 메시지 그대로다.</p>
     */
    private boolean webpushDataOnly = false;

    /**
     * 웹푸시 {@code Urgency: high}를 붙일 알림 종류(2026-10-30 사용자 선택). 절전 중인 기기도 바로 깨워 표시하게 한다.
     *
     * <p>기본(normal)으로는 안드로이드 절전 모드에서 알림이 묶여 늦게 뜬다(복약 알림 12:00 발송 → 12:16 표시).
     * 모든 종류에 붙이면 우선순위가 의미를 잃으므로 시간이 중요한 종류만 고른다. 웹이 아닌 토큰에는 효과가 없다.</p>
     */
    private Set<NotificationType> highUrgencyTypes = EnumSet.of(
            NotificationType.WARD_SOS,
            NotificationType.ANOMALY_DETECTED,
            NotificationType.ANOMALY_DETECTED_SELF,
            NotificationType.MEDICATION_REMINDER,
            NotificationType.ANOMALY_REVIEW_REQUIRED,
            NotificationType.CAMERA_LINK_LOST,
            NotificationType.CONNECTION_REQUEST,
            NotificationType.CONNECTION_ACCEPTED,
            NotificationType.CONNECTION_REFUSED,
            NotificationType.INQUIRY_ANSWERED);

    public boolean isHighUrgency(NotificationType type) {
        return type != null && highUrgencyTypes != null && highUrgencyTypes.contains(type);
    }
}
