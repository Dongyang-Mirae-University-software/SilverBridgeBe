package kr.silverbridge.main.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 키·토큰을 실어 보내는 외부 주소가 평문(http·ws)이면 기동 때 경고한다 (2026-11-10 전체 점검 L-4).
 *
 * <p>기본값은 모두 https·wss인데 환경변수를 잘못 넣으면 AI 키가 암호화 없이 나간다. 개발 환경(터널·내부망)에서 평문을
 * 쓰는 경우가 있어 기동을 막지는 않고 설정 이름만 WARN으로 남긴다 - 주소 값은 로그에 남기지 않는다.
 * 예약 관리자 주소({@code reservation.admin.base-url})는 같은 서버 내부망 평문이 의도라 대상이 아니다.</p>
 */
@Slf4j
@Component
public class ExternalUrlSchemeChecker {

    static final List<String> KEYS = List.of(
            "file-server.base-url",
            "camera.stream.ai-base-url",
            "medication.ocr.ai-base-url",
            "chat.relay.ai-base-url",
            "chat.stt.ai-base-url",
            "anomaly.ws-url");

    private final Environment environment;

    public ExternalUrlSchemeChecker(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnPlaintext() {
        List<String> plaintext = plaintextKeys();
        if (!plaintext.isEmpty()) {
            log.warn("[EXTERNAL-URL-PLAINTEXT] 암호화되지 않은 주소(http·ws)로 설정된 외부 연동: keys={}", plaintext);
        }
    }

    List<String> plaintextKeys() {
        List<String> plaintext = new ArrayList<>();
        for (String key : KEYS) {
            String value = environment.getProperty(key);
            if (value == null) {
                continue;
            }
            String lower = value.trim().toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("ws://")) {
                plaintext.add(key);
            }
        }
        return plaintext;
    }
}
