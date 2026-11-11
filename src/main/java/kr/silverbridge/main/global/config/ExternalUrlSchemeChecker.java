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
 * 루프백·사설 대역·점 없는 이름(같은 서버·내부망)은 경고하지 않는다.
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
            "anomaly.ws-url",
            // 안부 말벗(2026-11-04) - 대화·상태 확인·탈퇴 삭제가 모두 말벗 키를 헤더에 싣는다(2026-11-11 점검 L-1)
            "companion.ai-base-url");

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
            if ((lower.startsWith("http://") || lower.startsWith("ws://")) && !isInternalHost(hostOf(lower))) {
                plaintext.add(key);
            }
        }
        return plaintext;
    }

    /** {@code scheme://[user@]host[:port]/...}에서 호스트만. 이름 풀이(DNS)는 하지 않는다. */
    private static String hostOf(String lowerUrl) {
        String rest = lowerUrl.substring(lowerUrl.indexOf("://") + 3);
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String authority = rest.substring(0, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.startsWith("[")) { // IPv6 [::1]:8080
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(1, close) : authority;
        }
        int colon = authority.indexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    /**
     * 서버 밖으로 나가지 않는 주소 - 루프백·사설 대역(10/8, 172.16/12, 192.168/16)·점 없는 이름(도커 서비스 이름,
     * localhost). 같은 서버·내부망 호출은 평문이어도 경고하지 않는다(gosky STT가 도커 브리지 게이트웨이로 AI를 부른다,
     * 2026-11-10). 점이 있는 이름은 풀어 보지 않고 바깥 주소로 본다.
     */
    static boolean isInternalHost(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (host.equals("::1")) {
            return true;
        }
        if (!host.contains(".")) {
            return !host.contains(":"); // 점 없는 이름은 내부, 그 밖의 IPv6는 바깥으로 본다
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) {
                return false;
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 127
                || octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }
}
