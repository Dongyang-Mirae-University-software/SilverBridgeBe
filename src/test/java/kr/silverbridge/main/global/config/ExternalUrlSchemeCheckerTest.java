package kr.silverbridge.main.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 외부 연동 주소가 평문이면 설정 이름만 골라낸다 (2026-11-10 전체 점검 L-4). */
class ExternalUrlSchemeCheckerTest {

    @Test
    @DisplayName("https·wss·빈 값·없는 값은 경고 대상이 아니다")
    void secureOrEmptyIsFine() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("file-server.base-url", "https://files.example.test")
                .withProperty("camera.stream.ai-base-url", "HTTPS://ai.example.test")
                .withProperty("anomaly.ws-url", "wss://ai.example.test/ws")
                .withProperty("chat.stt.ai-base-url", "");

        assertThat(new ExternalUrlSchemeChecker(env).plaintextKeys()).isEmpty();
    }

    @Test
    @DisplayName("http·ws 주소는 설정 이름만 돌려준다(주소 값 없음), 예약 관리자 주소는 대상이 아니다")
    void plaintextKeysOnly() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("chat.relay.ai-base-url", " http://ai.example.test")
                .withProperty("anomaly.ws-url", "WS://ai.example.test/ws")
                .withProperty("medication.ocr.ai-base-url", "https://ai.example.test")
                .withProperty("reservation.admin.base-url", "http://reservation-api:6015");

        assertThat(new ExternalUrlSchemeChecker(env).plaintextKeys())
                .containsExactly("chat.relay.ai-base-url", "anomaly.ws-url");
    }

    @Test
    @DisplayName("같은 서버·내부망 주소(루프백·사설 대역·점 없는 이름)는 평문이어도 경고하지 않는다")
    void internalHostsAreNotWarned() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("chat.stt.ai-base-url", "http://10.0.22.1:6017")
                .withProperty("chat.relay.ai-base-url", "http://127.0.0.1:6017/api")
                .withProperty("camera.stream.ai-base-url", "http://ai-server:8000")
                .withProperty("medication.ocr.ai-base-url", "http://172.31.0.5")
                .withProperty("file-server.base-url", "http://192.168.0.10:9000")
                .withProperty("anomaly.ws-url", "ws://[::1]:6017/ws");

        assertThat(new ExternalUrlSchemeChecker(env).plaintextKeys()).isEmpty();
    }

    @Test
    @DisplayName("사설 대역처럼 보이지만 아닌 주소·공인 주소·점 있는 이름은 경고한다")
    void publicLookalikesAreWarned() {
        assertThat(ExternalUrlSchemeChecker.isInternalHost("172.32.0.1")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("172.15.0.1")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("11.0.0.1")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("192.169.0.1")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("10.0.0.1.example.test")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("ai.example.test")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("2001:db8::1")).isFalse();
        assertThat(ExternalUrlSchemeChecker.isInternalHost("")).isFalse();

        MockEnvironment env = new MockEnvironment()
                // 사용자 정보 칸에 사설 주소를 넣어도 실제 호스트로 판정한다
                .withProperty("chat.stt.ai-base-url", "http://10.0.0.1@ai.example.test/path");
        assertThat(new ExternalUrlSchemeChecker(env).plaintextKeys()).containsExactly("chat.stt.ai-base-url");
    }

    @Test
    @DisplayName("검사 대상 키는 application.yaml에 실제로 있는 설정이다")
    void keysExistInApplicationYaml() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/application.yaml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        // 마지막 칸 이름이 yaml에 있어야 한다(키 이름이 바뀌면 이 검사가 조용히 무력해지는 것을 막는다)
        for (String key : ExternalUrlSchemeChecker.KEYS) {
            String leaf = key.substring(key.lastIndexOf('.') + 1);
            assertThat(yaml).as(key).contains(leaf + ":");
        }
    }
}
