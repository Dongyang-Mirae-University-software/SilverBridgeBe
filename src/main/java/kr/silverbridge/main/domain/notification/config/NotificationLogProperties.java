package kr.silverbridge.main.domain.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림 발송 이력 보관 정책 (application.yaml {@code notification.log.*}).
 *
 * <p>이력 본문에는 피보호자 이름·카메라 위치 같은 생활 정보가 들어 있어 무기한 붙들지 않는다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "notification.log")
public class NotificationLogProperties {

    /** 이 일수보다 오래된 이력을 지운다(매일 04:30 KST). */
    private int retentionDays = 90;

    /** 보관 정리 킬 스위치. false면 정리만 멈춘다 - 기록·조회는 그대로 동작한다. */
    private boolean cleanupEnabled = true;
}
