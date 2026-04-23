package kr.silverbridge.main.domain.notification.service;

import com.google.firebase.messaging.*;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

/**
 * FCM 푸시 알림 발송 서비스
 * 토큰 등록/삭제 및 사용자별 알림 발송 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
    private final FcmTokenRepository fcmTokenRepository;

    // FCM 토큰 등록 (이미 존재하면 무시)
    @Transactional
    public void registerToken(String userId, String token, String platform) {
        if (fcmTokenRepository.findByToken(token).isPresent()) {
            return;
        }
        fcmTokenRepository.save(FcmToken.of(userId, token, platform));
        log.info("FCM 토큰 등록: userId={}", userId);
    }

    // FCM 토큰 삭제 (로그아웃 시)
    @Transactional
    public void deleteToken(String token) {
        fcmTokenRepository.deleteByToken(token);
    }

    // 특정 사용자에게 푸시 알림 발송 (등록된 모든 디바이스)
    public void sendToUser(String userId, String title, String body, Map<String, String> data) {
        List<FcmToken> tokens = fcmTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("FCM 토큰 없음: userId={}", userId);
            return;
        }

        List<String> tokenStrings = tokens.stream().map(FcmToken::getToken).toList();
        sendMulticast(tokenStrings, title, body, data);
    }

    // 여러 사용자에게 동시 발송
    public void sendToUsers(List<String> userIds, String title, String body, Map<String, String> data) {
        List<String> tokens = userIds.stream()
                .flatMap(uid -> fcmTokenRepository.findByUserId(uid).stream())
                .map(FcmToken::getToken)
                .toList();

        if (tokens.isEmpty()) {
            return;
        }
        sendMulticast(tokens, title, body, data);
    }

    // MulticastMessage로 최대 500개 토큰에 동시 발송
    private void sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            log.debug("FCM 초기화가 없어 알림 발송을 건너뜁니다.");
            return;
        }

        MulticastMessage.Builder builder = MulticastMessage.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .addAllTokens(tokens);

        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(builder.build());
            log.info("FCM 발송 완료: 성공={}, 실패={}", response.getSuccessCount(), response.getFailureCount());

            // 만료된 토큰 정리
            if (response.getFailureCount() > 0) {
                cleanupInvalidTokens(tokens, response);
            }
        } catch (FirebaseMessagingException e) {
            log.error("FCM 발송 실패: {}", e.getMessage());
        }
    }

    // 유효하지 않은 토큰 DB에서 삭제
    private void cleanupInvalidTokens(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful()) {
                String errorCode = sendResponse.getException().getMessagingErrorCode() != null
                        ? sendResponse.getException().getMessagingErrorCode().name()
                        : "UNKNOWN";
                // UNREGISTERED, INVALID_ARGUMENT 등 복구 불가 오류 시 토큰 삭제
                if ("UNREGISTERED".equals(errorCode) || "INVALID_ARGUMENT".equals(errorCode)) {
                    String invalidToken = tokens.get(i);
                    fcmTokenRepository.deleteByToken(invalidToken);
                    log.info("만료된 FCM 토큰 삭제: {}", invalidToken.substring(0, Math.min(20, invalidToken.length())));
                }
            }
        }
    }
}