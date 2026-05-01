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
import java.util.Base64;

@Slf4j
@Configuration
public class FcmConfig {

    // 서비스 계정 JSON을 base64로 인코딩해 환경변수에 보관한다.
    // 원본 JSON을 그대로 .env에 넣으면 docker compose의 env_file 파서가 private_key 내부의
    // \n 이스케이프를 실제 newline으로 변환해 JSON이 깨지는 문제가 있어, 영숫자+`+/=`만 있는
    // base64로 우회한다.
    @Value("${firebase.service-account-base64:}")
    private String serviceAccountBase64;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (serviceAccountBase64 == null || serviceAccountBase64.isBlank()) {
            throw new IllegalStateException(
                    "FIREBASE_SERVICE_ACCOUNT_BASE64 환경변수가 비어 있습니다.\n"
                            + "서비스 계정 JSON을 base64로 인코딩해 .env.dev에 한 줄로 넣으세요. 예:\n"
                            + "  base64 -w0 firebase-service-account.json\n"
                            + "  FIREBASE_SERVICE_ACCOUNT_BASE64=eyJ0eXBlIjoic2VydmljZV9hY2Nv...");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(serviceAccountBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "FIREBASE_SERVICE_ACCOUNT_BASE64 값이 유효한 base64가 아닙니다. "
                            + "`base64 -w0 firebase-service-account.json` 출력을 그대로 붙였는지 확인하세요.",
                    ex);
        }
        if (FirebaseApp.getApps().isEmpty()) {
            try (InputStream in = new ByteArrayInputStream(decoded)) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase 초기화 완료 (env base64 기반, decoded={} bytes)", decoded.length);
            }
        }
        return FirebaseMessaging.getInstance();
    }
}
