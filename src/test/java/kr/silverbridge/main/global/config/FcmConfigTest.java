package kr.silverbridge.main.global.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FCM 호출 시간 제한 고정 (2026-09-30 M-2 / QA XCUT-G11).
 *
 * <p>FCM이 느려지면 긴급 알림 스레드(SOS·화재)가 응답을 기다리며 묶인다. 연결 3초·응답 10초 제한이
 * 빠지면(0 = 무제한) 이 테스트가 깨진다.</p>
 */
class FcmConfigTest {

    @Test
    @DisplayName("FCM 옵션에 연결 3초·응답 10초 시간 제한이 걸린다")
    void 시간제한() {
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken("test-token", new Date(System.currentTimeMillis() + 3_600_000L)));

        FirebaseOptions options = FcmConfig.buildOptions(credentials);

        assertThat(options.getConnectTimeout()).isEqualTo(3_000);
        assertThat(options.getReadTimeout()).isEqualTo(10_000);
    }
}
