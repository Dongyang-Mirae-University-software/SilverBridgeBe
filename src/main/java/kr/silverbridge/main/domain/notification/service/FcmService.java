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

    // FCM 토큰 등록. 같은 토큰이 다른 사용자 소유로 남아 있으면(공유 디바이스에서 사용자 전환,
    // 이전 사용자가 로그아웃 시 삭제 API를 못 부른 경우) 소유자를 현재 사용자로 갱신한다 (M-S2-2)
    // — 그대로 두면 이전 사용자의 연결·SOS 알림이 현 사용자 기기에 계속 표시된다.
    @Transactional
    public void registerToken(String userId, String token, String platform) {
        fcmTokenRepository.findByToken(token).ifPresentOrElse(
                existing -> {
                    if (!existing.getUserId().equals(userId)) {
                        existing.reassignTo(userId, platform);
                        log.info("FCM 토큰 소유자 갱신(공유 디바이스): newUserId={}", userId);
                    }
                },
                () -> {
                    fcmTokenRepository.save(FcmToken.of(userId, token, platform));
                    log.info("FCM 토큰 등록: userId={}", userId);
                });
    }

    // FCM 토큰 삭제 (로그아웃 시) — 본인 소유 토큰만 삭제 (L-S2-3: 타인 토큰 무단 삭제 차단)
    @Transactional
    public void deleteToken(String userId, String token) {
        fcmTokenRepository.deleteByTokenAndUserId(token, userId);
    }

    // 사용자의 모든 FCM 토큰 삭제 (회원 탈퇴 시, D-USER-3)
    // 탈퇴는 soft delete(status=INACTIVE)라 user 행이 남아 FK CASCADE가 발동하지 않으므로 명시적으로 삭제한다.
    @Transactional
    public void deleteAllTokens(String userId) {
        fcmTokenRepository.deleteByUserId(userId);
        log.info("FCM 토큰 일괄 삭제(탈퇴): userId={}", userId);
    }

    // 특정 사용자에게 푸시 알림 발송 (등록된 모든 디바이스).
    // 반환: 1건 이상 실제 전달 성공 여부 — 토큰 없음/전 토큰 만료/발송 예외는 모두 false.
    // 필수(긴급) 알림의 결과 기반 SMS 폴백 판단에 사용한다 (M-S2-1).
    public boolean sendToUser(String userId, String title, String body, Map<String, String> data) {
        List<FcmToken> tokens = fcmTokenRepository.findByUserId(userId);
        if (tokens.isEmpty()) {
            log.debug("FCM 토큰 없음: userId={}", userId);
            return false;
        }

        List<String> tokenStrings = tokens.stream().map(FcmToken::getToken).toList();
        return sendMulticast(tokenStrings, title, body, data);
    }

    // MulticastMessage로 최대 500개 토큰에 동시 발송. 반환: 성공 1건 이상 여부.
    private boolean sendMulticast(List<String> tokens, String title, String body, Map<String, String> data) {
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
            return response.getSuccessCount() > 0;
        } catch (FirebaseMessagingException e) {
            log.error("FCM 발송 실패", e);
            return false;
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