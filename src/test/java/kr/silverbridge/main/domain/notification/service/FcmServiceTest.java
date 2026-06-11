package kr.silverbridge.main.domain.notification.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock private FirebaseMessaging firebaseMessaging;
    @Mock private FcmTokenRepository fcmTokenRepository;

    @InjectMocks private FcmService fcmService;

    private BatchResponse batchWithSingleFailure(MessagingErrorCode errorCode) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(errorCode);

        SendResponse failed = mock(SendResponse.class);
        when(failed.isSuccessful()).thenReturn(false);
        when(failed.getException()).thenReturn(exception);

        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getFailureCount()).thenReturn(1);
        when(batch.getResponses()).thenReturn(List.of(failed));
        return batch;
    }

    @Test
    @DisplayName("UNREGISTERED 실패 토큰은 DB에서 삭제 — 만료 토큰 정리로 hasToken이 false가 되어 SOS SMS 폴백이 살아난다 (H-S2-1)")
    void sendToUser_만료토큰_정리() throws FirebaseMessagingException {
        BatchResponse batch = batchWithSingleFailure(MessagingErrorCode.UNREGISTERED);
        when(fcmTokenRepository.findByUserId("GD0001"))
                .thenReturn(List.of(FcmToken.of("GD0001", "expired-token", "ANDROID")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        fcmService.sendToUser("GD0001", "긴급 SOS", "도움 요청", Map.of("type", "WARD_SOS"));

        verify(fcmTokenRepository).deleteByToken("expired-token");
    }

    @Test
    @DisplayName("일시 오류(UNAVAILABLE)는 복구 가능하므로 토큰을 삭제하지 않는다")
    void sendToUser_일시오류_토큰보존() throws FirebaseMessagingException {
        BatchResponse batch = batchWithSingleFailure(MessagingErrorCode.UNAVAILABLE);
        when(fcmTokenRepository.findByUserId("GD0001"))
                .thenReturn(List.of(FcmToken.of("GD0001", "live-token", "ANDROID")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        fcmService.sendToUser("GD0001", "제목", "본문", null);

        verify(fcmTokenRepository, never()).deleteByToken(any());
    }

    @Test
    @DisplayName("등록 토큰이 없으면 FCM 발송 자체를 시도하지 않는다")
    void sendToUser_토큰없음_미발송() throws FirebaseMessagingException {
        when(fcmTokenRepository.findByUserId("GD0001")).thenReturn(List.of());

        fcmService.sendToUser("GD0001", "제목", "본문", null);

        verify(firebaseMessaging, never()).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    @DisplayName("이미 등록된 토큰은 재등록하지 않는다 (멱등)")
    void registerToken_중복_무시() {
        when(fcmTokenRepository.findByToken("tok-1"))
                .thenReturn(Optional.of(FcmToken.of("GD0001", "tok-1", "ANDROID")));

        fcmService.registerToken("GD0001", "tok-1", "ANDROID");

        verify(fcmTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규 토큰은 저장한다")
    void registerToken_신규_저장() {
        when(fcmTokenRepository.findByToken("tok-2")).thenReturn(Optional.empty());

        fcmService.registerToken("GD0001", "tok-2", "ANDROID");

        verify(fcmTokenRepository).save(any(FcmToken.class));
    }
}
