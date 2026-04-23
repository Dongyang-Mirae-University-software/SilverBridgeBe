package kr.silverbridge.main.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Configuration
public class FcmConfig {

    @Value("${firebase.service-account-path:firebase-service-account.json}")
    private String serviceAccountPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        ClassPathResource resource = new ClassPathResource(serviceAccountPath);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Firebase 서비스 계정 파일을 찾을 수 없습니다: src/main/resources/" + serviceAccountPath
                            + "\n"
                            + "해당 파일을 프로젝트에 추가한 뒤 다시 실행하세요. "
                            + "(FCM 푸시 알림 기능은 이 파일 없이 동작할 수 없습니다.)");
        }
        if (FirebaseApp.getApps().isEmpty()) {
            try (InputStream inputStream = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(inputStream))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase 초기화 완료");
            }
        }
        return FirebaseMessaging.getInstance();
    }
}
