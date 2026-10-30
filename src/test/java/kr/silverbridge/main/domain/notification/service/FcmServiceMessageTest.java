package kr.silverbridge.main.domain.notification.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.config.FcmMessageProperties;
import kr.silverbridge.main.domain.notification.config.FcmTokenProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.FcmToken;
import kr.silverbridge.main.domain.notification.repository.FcmTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FCM 메시지 모양(2026-10-30 웹푸시 문구·data 전용) 검증.
 *
 * <p>Firebase 메시지 클래스는 읽기 접근자가 없어 필드를 반사로 읽는다. 전달 판정(ChannelResult)은
 * 웹 토큰을 나눠 보내도 기존 규칙(성공 1건 이상 = 전달, 전부 만료 = 만료, 그 밖 = 서버 오류)과 같아야 한다 -
 * 이 값이 SOS 문자 대체·쿨다운 해제 판단의 근거다.</p>
 */
@ExtendWith(MockitoExtension.class)
class FcmServiceMessageTest {

    private static final String USER = "GD0001";
    private static final Map<String, String> DATA = Map.of("type", "WARD_SOS", "wardId", "WD0001");

    @Mock private FirebaseMessaging firebaseMessaging;
    @Mock private FcmTokenRepository fcmTokenRepository;
    @Spy private FcmTokenProperties tokenProperties = new FcmTokenProperties();
    @Spy private FcmMessageProperties messageProperties = new FcmMessageProperties();

    @InjectMocks private FcmService fcmService;

    // ---- 메시지 필드 읽기 ----

    private static Object field(Object target, String name) {
        return ReflectionTestUtils.getField(target, name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tokensOf(MulticastMessage m) {
        return (List<String>) field(m, "tokens");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> dataOf(MulticastMessage m) {
        return (Map<String, String>) field(m, "data");
    }

    private static Object notificationOf(MulticastMessage m) {
        return field(m, "notification");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> webpushHeadersOf(MulticastMessage m) {
        Object webpush = field(m, "webpushConfig");
        return webpush == null ? null : (Map<String, String>) field(webpush, "headers");
    }

    // ---- 픽스처 ----

    private void tokens(FcmToken... tokens) {
        when(fcmTokenRepository.findByUserId(USER)).thenReturn(List.of(tokens));
    }

    private static BatchResponse success(int count) {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getSuccessCount()).thenReturn(count);
        return batch;
    }

    /** 전부 UNREGISTERED(만료)로 실패한 묶음. */
    private static BatchResponse allExpired(int count) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        SendResponse failed = mock(SendResponse.class);
        when(failed.isSuccessful()).thenReturn(false);
        when(failed.getException()).thenReturn(exception);
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getFailureCount()).thenReturn(count);
        when(batch.getResponses()).thenReturn(java.util.Collections.nCopies(count, failed));
        return batch;
    }

    private List<MulticastMessage> sentMessages(int times) throws FirebaseMessagingException {
        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging, times(times)).sendEachForMulticast(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("data에 제목·본문을 싣는다 (모든 종류·모든 수신자)")
    class DataTitleBody {

        @Test
        @DisplayName("data에 title·body(줄바꿈 그대로)를 더하고 호출자 data 키는 유지한다")
        void dataCarriesTitleAndBody() throws FirebaseMessagingException {
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, NotificationType.WARD_SOS, "[긴급] 김순자님 SOS", "첫 줄.\n둘째 줄.", DATA);

            assertThat(dataOf(sentMessages(1).get(0)))
                    .containsEntry("title", "[긴급] 김순자님 SOS")
                    .containsEntry("body", "첫 줄.\n둘째 줄.")
                    .containsEntry("type", "WARD_SOS")
                    .containsEntry("wardId", "WD0001");
        }

        @Test
        @DisplayName("호출자 data가 없어도 title·body는 실린다")
        void nullDataStillCarriesTitleAndBody() throws FirebaseMessagingException {
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, "제목", "본문", null);

            assertThat(dataOf(sentMessages(1).get(0))).containsOnlyKeys("title", "body");
        }
    }

    @Nested
    @DisplayName("webpush-data-only = false (기본)")
    class DataOnlyOff {

        @Test
        @DisplayName("기존과 같이 모든 토큰을 한 메시지로, notification 블록을 담아 보낸다")
        void singleMessageWithNotification() throws FirebaseMessagingException {
            tokens(FcmToken.of(USER, "web-1", "WEB"), FcmToken.of(USER, "android-1", "ANDROID"));
            BatchResponse ok = success(2);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            ChannelResult result = fcmService.sendToUser(USER, NotificationType.MEDICATION_MISSED, "제목", "본문", DATA);

            MulticastMessage message = sentMessages(1).get(0);
            assertThat(tokensOf(message)).containsExactly("web-1", "android-1");
            assertThat(field(notificationOf(message), "title")).isEqualTo("제목");
            assertThat(field(notificationOf(message), "body")).isEqualTo("본문");
            // normal 종류는 webpush 설정을 붙이지 않는다 - 바꾸기 전 메시지와 같다(data에 title·body만 더해짐)
            assertThat(field(message, "webpushConfig")).isNull();
            assertThat(result).isEqualTo(ChannelResult.delivered());
        }
    }

    @Nested
    @DisplayName("webpush-data-only = true")
    class DataOnlyOn {

        @Test
        @DisplayName("웹 토큰은 notification 블록 없이 data만, 웹이 아닌 토큰은 기존대로 보낸다")
        void webTokensGetDataOnly() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"), FcmToken.of(USER, "android-1", "ANDROID"),
                    FcmToken.of(USER, "web-2", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, NotificationType.WARD_SOS, "제목", "본문", DATA);

            List<MulticastMessage> messages = sentMessages(2);
            MulticastMessage others = messages.get(0);
            MulticastMessage web = messages.get(1);
            assertThat(tokensOf(others)).containsExactly("android-1");
            assertThat(notificationOf(others)).isNotNull();
            assertThat(tokensOf(web)).containsExactly("web-1", "web-2");
            assertThat(notificationOf(web)).as("웹은 SW 혼자 그린다 - 자동 표시 블록이 있으면 두 번 뜬다").isNull();
            assertThat(dataOf(web)).containsEntry("title", "제목").containsEntry("body", "본문");
        }

        @Test
        @DisplayName("웹 토큰만 있으면 한 번만 보낸다")
        void webOnlyUserSendsOnce() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            ChannelResult result = fcmService.sendToUser(USER, NotificationType.WARD_SOS, "제목", "본문", DATA);

            assertThat(notificationOf(sentMessages(1).get(0))).isNull();
            assertThat(result).isEqualTo(ChannelResult.delivered());
        }

        @Test
        @DisplayName("전달 판정: 한쪽 묶음만 성공해도 전달이다")
        void deliveredWhenEitherGroupSucceeds() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"), FcmToken.of(USER, "android-1", "ANDROID"));
            BatchResponse expiredOthers = allExpired(1);
            BatchResponse okWeb = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                    .thenReturn(expiredOthers, okWeb);

            assertThat(fcmService.sendToUser(USER, NotificationType.WARD_SOS, "제목", "본문", DATA))
                    .isEqualTo(ChannelResult.delivered());
        }

        @Test
        @DisplayName("전달 판정: 두 묶음 전부 만료면 ALL_TOKENS_EXPIRED - 나눠 보내기 전과 같다")
        void allExpiredAcrossGroups() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"), FcmToken.of(USER, "android-1", "ANDROID"));
            BatchResponse first = allExpired(1);
            BatchResponse second = allExpired(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(first, second);

            assertThat(fcmService.sendToUser(USER, NotificationType.WARD_SOS, "제목", "본문", DATA))
                    .isEqualTo(ChannelResult.failed(ChannelFailureReason.ALL_TOKENS_EXPIRED));
            verify(fcmTokenRepository).deleteByToken("web-1");
            verify(fcmTokenRepository).deleteByToken("android-1");
        }

        @Test
        @DisplayName("전달 판정: 한 묶음은 만료, 다른 묶음은 발송 예외면 PUSH_SERVER_ERROR")
        void expiredPlusExceptionIsServerError() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"), FcmToken.of(USER, "android-1", "ANDROID"));
            FirebaseMessagingException boom = mock(FirebaseMessagingException.class);
            BatchResponse expired = allExpired(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                    .thenReturn(expired)
                    .thenThrow(boom);

            assertThat(fcmService.sendToUser(USER, NotificationType.WARD_SOS, "제목", "본문", DATA))
                    .isEqualTo(ChannelResult.failed(ChannelFailureReason.PUSH_SERVER_ERROR));
        }
    }

    @Nested
    @DisplayName("웹푸시 우선순위(Urgency)")
    class Urgency {

        @Test
        @DisplayName("기본 high 목록은 2026-10-30 사용자 선택 10종이다")
        void defaultHighUrgencyTypes() {
            assertThat(new FcmMessageProperties().getHighUrgencyTypes()).containsExactlyInAnyOrderElementsOf(EnumSet.of(
                    NotificationType.WARD_SOS, NotificationType.ANOMALY_DETECTED, NotificationType.ANOMALY_DETECTED_SELF,
                    NotificationType.MEDICATION_REMINDER, NotificationType.ANOMALY_REVIEW_REQUIRED,
                    NotificationType.CAMERA_LINK_LOST, NotificationType.CONNECTION_REQUEST,
                    NotificationType.CONNECTION_ACCEPTED, NotificationType.CONNECTION_REFUSED,
                    NotificationType.INQUIRY_ANSWERED));
        }

        @Test
        @DisplayName("high 종류는 Urgency: high 헤더를 붙인다(data 전용 여부와 무관)")
        void highUrgencyHeader() throws FirebaseMessagingException {
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, NotificationType.ANOMALY_DETECTED, "제목", "본문", DATA);

            assertThat(webpushHeadersOf(sentMessages(1).get(0))).containsEntry("Urgency", "high");
        }

        @Test
        @DisplayName("data 전용 웹 메시지에도 Urgency: high가 붙는다")
        void highUrgencyOnDataOnly() throws FirebaseMessagingException {
            messageProperties.setWebpushDataOnly(true);
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, NotificationType.MEDICATION_REMINDER, "제목", "본문", DATA);

            assertThat(webpushHeadersOf(sentMessages(1).get(0))).containsEntry("Urgency", "high");
        }

        @Test
        @DisplayName("목록에 없는 종류·종류를 모르는 호출은 우선순위를 붙이지 않는다")
        void normalUrgency() throws FirebaseMessagingException {
            tokens(FcmToken.of(USER, "web-1", "WEB"));
            BatchResponse ok = success(1);
            when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(ok);

            fcmService.sendToUser(USER, NotificationType.CONNECTION_FORCED, "제목", "본문", DATA);
            fcmService.sendToUser(USER, "제목", "본문", DATA);

            assertThat(sentMessages(2)).allSatisfy(m -> assertThat(field(m, "webpushConfig")).isNull());
        }
    }
}
