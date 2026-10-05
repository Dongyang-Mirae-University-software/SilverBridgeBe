package kr.silverbridge.main.domain.sos.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SOS 설정 (application.yaml {@code sos.*}).
 *
 * <p>기본값은 코드와 서버가 같게 유지한다 - 서버만 환경변수로 덮어써 로컬만 다르게 동작하던
 * 이상감지 사례(2026-07-28)를 반복하지 않기 위함이다.</p>
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "sos")
public class SosProperties {

    /** 쿨다운 기본값(초). 범위를 벗어난 설정값의 대체값이기도 하다. */
    public static final int DEFAULT_NOTIFY_COOLDOWN_SECONDS = 10;

    /** 쿨다운 상한(초). 이보다 길면 반복 신호를 너무 오래 억제한다. */
    public static final int MAX_NOTIFY_COOLDOWN_SECONDS = 300;

    /**
     * 동일 피보호자의 SOS <b>알림</b> 발송 최소 간격(초). 이력 저장과는 무관하다.
     *
     * <p>목적은 보호자 폰에 같은 알림이 폭주하는 것(alarm fatigue)을 막는 것이지 서버 부하 방어가 아니다.
     * SOS에서는 반복 입력이 위급 신호라 길게 잡지 않는다.</p>
     */
    private int notifyCooldownSeconds = DEFAULT_NOTIFY_COOLDOWN_SECONDS;

    /**
     * 실제로 적용할 쿨다운(초). 1~300 밖의 값(0 이하 포함)은 기본값으로 대체하고 WARN을 남긴다.
     *
     * <p>Redis {@code EX}에 0 이하를 넘기면 예외가 나는데, 쿨다운은 fail-open이라 조용히 꺼져 버린다. 그래서 여기서 막는다.</p>
     */
    public int effectiveNotifyCooldownSeconds() {
        if (notifyCooldownSeconds < 1 || notifyCooldownSeconds > MAX_NOTIFY_COOLDOWN_SECONDS) {
            log.warn("sos.notify-cooldown-seconds={} 은(는) 허용 범위(1~{})를 벗어나 기본값 {}초를 사용합니다.",
                    notifyCooldownSeconds, MAX_NOTIFY_COOLDOWN_SECONDS, DEFAULT_NOTIFY_COOLDOWN_SECONDS);
            return DEFAULT_NOTIFY_COOLDOWN_SECONDS;
        }
        return notifyCooldownSeconds;
    }
}
