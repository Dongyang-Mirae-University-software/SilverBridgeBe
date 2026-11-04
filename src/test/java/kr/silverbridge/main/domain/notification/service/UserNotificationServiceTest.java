package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.config.NotificationLogProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.UserNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.UserNotificationItem;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class UserNotificationServiceTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";

    @Mock private NotificationLogRepository repository;
    @Mock private UserRepository userRepository;

    private UserNotificationService service;

    @BeforeEach
    void setUp() {
        NotificationLogProperties properties = new NotificationLogProperties();
        properties.setRetentionDays(90);
        service = new UserNotificationService(repository, userRepository, properties);
    }

    @Test
    @DisplayName("목록: 토큰 사용자·90일 하한·묶음 종류로 조회하고, 피보호자 이름은 한 번에 채운다")
    void 목록_조회_조건과_이름() {
        NotificationLog sos = log(1L, GUARDIAN_ID, WARD_ID, NotificationType.WARD_SOS, null);
        NotificationLog inquiry = log(2L, GUARDIAN_ID, null, NotificationType.INQUIRY_ANSWERED, OffsetDateTime.now());
        given(repository.searchForRecipient(eq(GUARDIAN_ID), any(), anyBoolean(), anyCollection(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(sos, inquiry)));
        User ward = mock(User.class);
        given(ward.getId()).willReturn(WARD_ID);
        given(ward.getName()).willReturn("김영자");
        given(userRepository.findAllById(any())).willReturn(List.of(ward));

        PageResponse<UserNotificationItem> page = service.getMyNotifications(GUARDIAN_ID, UserNotificationCategory.URGENT, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NotificationType>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).searchForRecipient(eq(GUARDIAN_ID), from.capture(), eq(true), types.capture(), any(Pageable.class));
        assertThat(types.getValue()).containsExactlyInAnyOrderElementsOf(UserNotificationCategory.URGENT.types());
        assertThat(from.getValue()).isCloseTo(OffsetDateTime.now().minusDays(90), within(java.time.Duration.ofMinutes(1)));

        assertThat(page.content()).extracting(UserNotificationItem::id, UserNotificationItem::category,
                        UserNotificationItem::read, UserNotificationItem::wardId, UserNotificationItem::wardName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, UserNotificationCategory.URGENT, false, WARD_ID, "김영자"),
                        org.assertj.core.groups.Tuple.tuple(2L, UserNotificationCategory.OTHER, true, null, null));
    }

    @Test
    @DisplayName("목록: 묶음이 없으면 전 종류를 넘기고(빈 IN 절 회피), 본인에 관한 알림은 이름 조회 없이 wardId·wardName이 null")
    void 목록_본인_알림() {
        NotificationLog reminder = log(3L, WARD_ID, WARD_ID, NotificationType.MEDICATION_REMINDER, null);
        given(repository.searchForRecipient(eq(WARD_ID), any(), eq(false), anyCollection(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(reminder)));

        PageResponse<UserNotificationItem> page = service.getMyNotifications(WARD_ID, null, 0, 20);

        verify(repository).searchForRecipient(eq(WARD_ID), any(), eq(false),
                eq(EnumSet.allOf(NotificationType.class)), any(Pageable.class));
        verify(userRepository, never()).findAllById(any());
        assertThat(page.content().get(0).wardId()).isNull();
        assertThat(page.content().get(0).wardName()).isNull();
    }

    @Test
    @DisplayName("읽음: 본인 것이면 UPDATE 한 번으로 끝난다(이미 읽은 것도 1을 돌려줘 멱등)")
    void 읽음_성공() {
        given(repository.markRead(eq(10L), eq(GUARDIAN_ID), any())).willReturn(1);

        assertThatNoException().isThrownBy(() -> service.markRead(GUARDIAN_ID, 10L));
        verify(repository, never()).findRecipientIdById(any());
    }

    @Test
    @DisplayName("읽음: 남의 알림 → 403 NOTIFICATION_NOT_OWNED + [IDOR-ATTEMPT] (제목·본문·소유자 ID는 로그에 없다)")
    void 읽음_남의것(CapturedOutput output) {
        given(repository.markRead(eq(10L), eq(GUARDIAN_ID), any())).willReturn(0);
        given(repository.findRecipientIdById(10L)).willReturn(Optional.of("GD0002"));

        assertThatThrownBy(() -> service.markRead(GUARDIAN_ID, 10L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_OWNED);
        assertThat(output.getOut()).contains("[IDOR-ATTEMPT]", "userId=GD0001", "notificationId=10")
                .doesNotContain("GD0002");
    }

    @Test
    @DisplayName("읽음: 없는 알림 → 404, 본인의 보내지 않은(NOT_SENT) 알림도 목록에 없으므로 404")
    void 읽음_없음() {
        given(repository.markRead(any(), eq(GUARDIAN_ID), any())).willReturn(0);
        given(repository.findRecipientIdById(11L)).willReturn(Optional.empty());
        given(repository.findRecipientIdById(12L)).willReturn(Optional.of(GUARDIAN_ID));

        assertThatThrownBy(() -> service.markRead(GUARDIAN_ID, 11L))
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        assertThatThrownBy(() -> service.markRead(GUARDIAN_ID, 12L))
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("모두 읽음·안 읽은 수는 토큰 사용자 기준이다")
    void 모두_읽음과_개수() {
        given(repository.markAllRead(eq(WARD_ID), any())).willReturn(3);
        given(repository.countUnread(eq(WARD_ID), any())).willReturn(0L);

        assertThat(service.markAllRead(WARD_ID).updated()).isEqualTo(3);
        assertThat(service.countUnread(WARD_ID).count()).isZero();
    }

    private static NotificationLog log(Long id, String recipientId, String wardId, NotificationType type,
                                       OffsetDateTime readAt) {
        NotificationLog log = NotificationLog.builder()
                .createdAt(OffsetDateTime.now()).type(type).recipientId(recipientId).wardId(wardId)
                .title("제목").body("본문").result(NotificationLogResult.DELIVERED).build();
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "readAt", readAt);
        return log;
    }
}
