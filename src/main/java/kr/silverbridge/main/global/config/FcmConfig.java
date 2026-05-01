package kr.silverbridge.main.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class FcmConfig {

    // 시크릿을 JAR/이미지에 굽지 않기 위해 환경변수(.env.dev)에서 JSON 전체를 받는다.
    // 마운트·classpath 방식은 호스트 파일 누락 시 Docker가 빈 디렉토리를 자동 생성하는 함정이 있어 제거.
    @Value("${firebase.service-account-json:}")
    private String serviceAccountJson;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new IllegalStateException(
                    "FIREBASE_SERVICE_ACCOUNT_JSON 환경변수가 비어 있습니다.\n"
                            + ".env.dev에 한 줄로 JSON 전체를 넣으세요. 예:\n"
                            + "FIREBASE_SERVICE_ACCOUNT_JSON={\"type\":\"service_account\",\"project_id\":\"...\",...}");
        }
        if (FirebaseApp.getApps().isEmpty()) {
            try (InputStream in = new ByteArrayInputStream(
                    serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase 초기화 완료 (env JSON 기반)");
            }
        }
        return FirebaseMessaging.getInstance();
    }
}
