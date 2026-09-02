package kr.silverbridge.main.domain.notification.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import kr.silverbridge.main.domain.notification.config.FcmTokenProperties;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock private FirebaseMessaging firebaseMessaging;
    @Mock private FcmTokenRepository fcmTokenRepository;
    /** 기본값(상한 5 · 유휴 60일)을 그대로 쓰되 케이스별로 조정한다. */
    @Spy private FcmTokenProperties tokenProperties = new FcmTokenProperties();

    @InjectMocks private FcmService fcmService;

    /** updatedAt이 원하는 시각인 토큰. 상한 적용 순서를 확인하려면 이 값을 직접 정해야 한다. */
    private FcmToken tokenAgedDays(String userId, String token, int daysAgo) {
        FcmToken fcmToken = FcmToken.of(userId, token, "WEB");
        ReflectionTestUtils.setField(fcmToken, "updatedAt", OffsetDateTime.now().minusDays(daysAgo));
        return fcmToken;
    }

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
    @DisplayName("UNREGISTERED 실패 토큰은 DB에서 삭제 + 전달 실패(false) 반환 — SMS 폴백 판단 근거 (H-S2-1/M-S2-1)")
    void sendToUser_만료토큰_정리_및_실패반환() throws FirebaseMessagingException {
        BatchResponse batch = batchWithSingleFailure(MessagingErrorCode.UNREGISTERED);
        when(fcmTokenRepository.findByUserId("GD0001"))
                .thenReturn(List.of(FcmToken.of("GD0001", "expired-token", "ANDROID")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        boolean delivered = fcmService.sendToUser("GD0001", "긴급 SOS", "도움 요청", Map.of("type", "WARD_SOS"));

        org.assertj.core.api.Assertions.assertThat(delivered).isFalse();
        verify(fcmTokenRepository).deleteByToken("expired-token");
    }

    @Test
    @DisplayName("1건 이상 전달 성공 시 true 반환 — SMS 폴백 생략 근거 (M-S2-1)")
    void sendToUser_전달성공_true() throws FirebaseMessagingException {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getSuccessCount()).thenReturn(1);
        when(batch.getFailureCount()).thenReturn(0);
        when(fcmTokenRepository.findByUserId("GD0001"))
                .thenReturn(List.of(FcmToken.of("GD0001", "live-token", "ANDROID")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        boolean delivered = fcmService.sendToUser("GD0001", "긴급 SOS", "도움 요청", null);

        org.assertj.core.api.Assertions.assertThat(delivered).isTrue();
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
    @DisplayName("등록 토큰이 없으면 FCM 발송을 시도하지 않고 false 반환 — SMS 폴백으로 이어진다")
    void sendToUser_토큰없음_미발송() throws FirebaseMessagingException {
        when(fcmTokenRepository.findByUserId("GD0001")).thenReturn(List.of());

        boolean delivered = fcmService.sendToUser("GD0001", "제목", "본문", null);

        org.assertj.core.api.Assertions.assertThat(delivered).isFalse();
        verify(firebaseMessaging, never()).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    @DisplayName("같은 사용자의 기존 토큰 재등록 → 저장·갱신 없이 멱등 처리")
    void registerToken_중복_무시() {
        FcmToken existing = FcmToken.of("GD0001", "tok-1", "ANDROID");
        when(fcmTokenRepository.findByToken("tok-1")).thenReturn(Optional.of(existing));

        fcmService.registerToken("GD0001", "tok-1", "ANDROID");

        verify(fcmTokenRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(existing.getUserId()).isEqualTo("GD0001");
    }

    @Test
    @DisplayName("다른 사용자 소유 토큰 재등록(공유 디바이스) → 소유자를 현재 사용자로 갱신 (M-S2-2)")
    void registerToken_타인소유_소유자갱신() {
        FcmToken ownedByPrevUser = FcmToken.of("GD0001", "tok-1", "ANDROID");
        when(fcmTokenRepository.findByToken("tok-1")).thenReturn(Optional.of(ownedByPrevUser));

        fcmService.registerToken("WD0002", "tok-1", "IOS");

        verify(fcmTokenRepository, never()).save(any());
        org.assertj.core.api.Assertions.assertThat(ownedByPrevUser.getUserId()).isEqualTo("WD0002");
        org.assertj.core.api.Assertions.assertThat(ownedByPrevUser.getPlatform()).isEqualTo("IOS");
    }

    @Test
    @DisplayName("토큰 삭제는 본인 소유 조건이 포함된 쿼리로 위임된다 (L-S2-3)")
    void deleteToken_본인소유만() {
        fcmService.deleteToken("GD0001", "tok-1");

        verify(fcmTokenRepository).deleteByTokenAndUserId("tok-1", "GD0001");
    }

    @Test
    @DisplayName("신규 토큰은 저장한다")
    void registerToken_신규_저장() {
        when(fcmTokenRepository.findByToken("tok-2")).thenReturn(Optional.empty());

        fcmService.registerToken("GD0001", "tok-2", "ANDROID");

        verify(fcmTokenRepository).save(any(FcmToken.class));
    }
    @Test
    @DisplayName("같은 사용자가 재등록하면 행을 늘리지 않고 마지막 사용 시각만 갱신한다")
    void registerToken_재등록_시각갱신() {
        when(fcmTokenRepository.findByToken("tok-1"))
                .thenReturn(Optional.of(FcmToken.of("GD0001", "tok-1", "WEB")));

        fcmService.registerToken("GD0001", "tok-1", "WEB");

        // 갱신이 없으면 "아직 쓰이는 토큰"임을 알 방법이 없어 유휴 정리에 잘못 걸린다
        verify(fcmTokenRepository).touch(eq("tok-1"), any(OffsetDateTime.class));
        verify(fcmTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("상한을 넘으면 마지막 사용이 오래된 것부터 삭제한다")
    void registerToken_상한초과_오래된것부터_삭제() {
        tokenProperties.setMaxPerUser(2);
        when(fcmTokenRepository.findByToken("tok-new")).thenReturn(Optional.empty());
        when(fcmTokenRepository.findByUserIdOrderByUpdatedAtDesc("GD0001")).thenReturn(List.of(
                tokenAgedDays("GD0001", "tok-new", 0),
                tokenAgedDays("GD0001", "tok-recent", 3),
                tokenAgedDays("GD0001", "tok-old", 40),
                tokenAgedDays("GD0001", "tok-oldest", 90)));

        fcmService.registerToken("GD0001", "tok-new", "WEB");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FcmToken>> captor = ArgumentCaptor.forClass(List.class);
        verify(fcmTokenRepository).deleteAll(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .extracting(FcmToken::getToken)
                .containsExactly("tok-old", "tok-oldest");
    }

    @Test
    @DisplayName("상한 이내면 아무것도 지우지 않는다 - 폰·PC를 함께 쓰는 것은 정상이다")
    void registerToken_상한이내_삭제없음() {
        when(fcmTokenRepository.findByToken("tok-new")).thenReturn(Optional.empty());
        when(fcmTokenRepository.findByUserIdOrderByUpdatedAtDesc("GD0001")).thenReturn(List.of(
                tokenAgedDays("GD0001", "tok-new", 0),
                tokenAgedDays("GD0001", "tok-phone", 5),
                tokenAgedDays("GD0001", "tok-pc", 9)));

        fcmService.registerToken("GD0001", "tok-new", "WEB");

        verify(fcmTokenRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("재등록은 개수를 늘리지 않으므로 상한 검사를 하지 않는다")
    void registerToken_재등록은_상한검사_안함() {
        when(fcmTokenRepository.findByToken("tok-1"))
                .thenReturn(Optional.of(FcmToken.of("GD0001", "tok-1", "WEB")));

        fcmService.registerToken("GD0001", "tok-1", "WEB");

        verify(fcmTokenRepository, never()).findByUserIdOrderByUpdatedAtDesc(any());
    }

    @Test
    @DisplayName("유휴 정리는 설정한 일수 이전을 기준으로 삭제한다")
    void cleanupStaleTokens_기준시각() {
        tokenProperties.setStaleDays(60);
        when(fcmTokenRepository.deleteStaleTokens(any(OffsetDateTime.class))).thenReturn(3);

        org.assertj.core.api.Assertions.assertThat(fcmService.cleanupStaleTokens()).isEqualTo(3);

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(fcmTokenRepository).deleteStaleTokens(captor.capture());
        // 경계가 흔들리지 않게 하루 폭으로 검증한다(실행 시각에 의존하지 않도록)
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isBefore(OffsetDateTime.now().minusDays(59))
                .isAfter(OffsetDateTime.now().minusDays(61));
    }
}
