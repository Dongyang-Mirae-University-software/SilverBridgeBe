package kr.silverbridge.main.domain.notification.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 필수 알림(SOS)의 문자 폴백 상한 (application.yaml {@code notification.sms-fallback.*}).
 *
 * <p>푸시를 못 받는 수신자에게 문자가 유일한 수단이라 상한은 높게 잡는다. 목적은 연타·호출 루프에서 문자 비용과
 * 수신자 피로가 끝없이 커지는 것을 막는 것이다. 푸시·WebSocket·이력에는 영향이 없다.</p>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "notification.sms-fallback")
public class SmsFallbackProperties {

    /** 기본 상한(건/시간). 10초 간격으로 쉬지 않고 눌러도 5분이면 닿는 값이다. */
    public static final int DEFAULT_MAX_PER_HOUR = 30;

    /**
     * 수신자당 시간당 문자 폴백 최대 건수(고정 1시간 창). {@code 0}이면 상한을 끈다.
     */
    private int maxPerHour = DEFAULT_MAX_PER_HOUR;

    /**
     * 실제로 적용할 상한. 음수는 기본값으로 대체하고 WARN을 남긴다(0은 "끔"이라 그대로 둔다).
     * 숫자가 아닌 값은 바인딩 단계에서 기동 실패한다(다른 설정 클래스와 같은 fail-fast).
     */
    public int effectiveMaxPerHour() {
        if (maxPerHour < 0) {
            log.warn("notification.sms-fallback.max-per-hour={} 은(는) 음수라 기본값 {}건을 사용합니다.",
                    maxPerHour, DEFAULT_MAX_PER_HOUR);
            return DEFAULT_MAX_PER_HOUR;
        }
        return maxPerHour;
    }
}
