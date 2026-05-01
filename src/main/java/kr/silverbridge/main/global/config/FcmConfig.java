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
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Configuration
public class FcmConfig {

    @Value("${firebase.service-account-path:firebase-service-account.json}")
    private String serviceAccountPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            try (InputStream inputStream = openServiceAccountStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(inputStream))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase 초기화 완료 (key: {})", serviceAccountPath);
            }
        }
        return FirebaseMessaging.getInstance();
    }

    // 운영 환경(docker volume 마운트)은 절대 경로, 로컬은 src/main/resources의 클래스패스 — 둘 다 지원.
    private InputStream openServiceAccountStream() throws IOException {
        Path filePath = Path.of(serviceAccountPath);
        if (Files.isReadable(filePath)) {
            return Files.newInputStream(filePath);
        }
        ClassPathResource resource = new ClassPathResource(serviceAccountPath);
        if (resource.exists()) {
            return resource.getInputStream();
        }
        throw new IllegalStateException(
                "Firebase 서비스 계정 키 파일을 찾을 수 없습니다.\n"
                        + " - 시도한 경로: " + filePath.toAbsolutePath() + " (filesystem)\n"
                        + " - 시도한 경로: classpath:" + serviceAccountPath + "\n"
                        + "운영 환경에서는 firebase-service-account.json을 컨테이너에 볼륨 마운트하거나,\n"
                        + "로컬에서는 src/main/resources/ 아래에 두세요.\n"
                        + "FIREBASE_SERVICE_ACCOUNT_PATH 환경변수로 경로를 명시할 수 있습니다.");
    }
}
