package kr.silverbridge.main.domain.notification.service;

import com.google.firebase.messaging.*;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final FirebaseMessaging firebaseMessaging;
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

    // 사용자의 모든 FCM 토큰 삭제 (회원 탈퇴 시, D-USER-3)
    // 탈퇴는 soft delete(status=INACTIVE)라 user 행이 남아 FK CASCADE가 발동하지 않으므로 명시적으로 삭제한다.
    @Transactional
    public void deleteAllTokens(String userId) {
        fcmTokenRepository.deleteByUserId(userId);
        log.info("FCM 토큰 일괄 삭제(탈퇴): userId={}", userId);
    }

    // 사용자에게 등록된 FCM 토큰이 하나라도 있는지. 긴급 알림 SMS 폴백 판단용(토큰 없으면 푸시가 닿지 않음).
    @Transactional(readOnly = true)
    public boolean hasToken(String userId) {
        return fcmTokenRepository.existsByUserId(userId);
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