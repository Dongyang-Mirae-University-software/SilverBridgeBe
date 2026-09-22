package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationItem;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationPeriod;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationSummaryResponse;
import kr.silverbridge.main.domain.notification.entity.ChannelAttempt;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.entity.NotificationNotSentReason;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";

    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private ConnectionRepository connectionRepository;

    @InjectMocks private AdminNotificationService service;

    @Test
    @DisplayName("요약: 전송 완료는 문자 대체를 포함하고, 보내지 않음은 실패와 따로 센다")
    void 요약_합산() {
        when(notificationLogRepository.countByResult(any(), anyBoolean(), anyCollection(), anyBoolean(), anyCollection()))
                .thenReturn(List.of(
                        count(NotificationLogResult.DELIVERED, 6),
                        count(NotificationLogResult.SMS_FALLBACK, 1),
                        count(NotificationLogResult.FAILED, 2),
                        count(NotificationLogResult.NOT_SENT, 2)));

        AdminNotificationSummaryResponse summary = service.getSummary(null, null, null);

        assertThat(summary.total()).isEqualTo(11);
        assertThat(summary.delivered()).isEqualTo(7);
        assertThat(summary.smsFallback()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(2);
        assertThat(summary.notSent()).isEqualTo(2);
        assertThat(summary.period()).isEqualTo(AdminNotificationPeriod.LAST_7_DAYS);
    }

    @Test
    @DisplayName("요약: 한 건도 없으면 모두 0 - 집계된 결과가 없다는 뜻이라 0이 맞다")
    void 요약_빈값() {
        when(notificationLogRepository.countByResult(any(), anyBoolean(), anyCollection(), anyBoolean(), anyCollection()))
                .thenReturn(List.of());

        AdminNotificationSummaryResponse summary = service.getSummary(AdminNotificationCategory.SOS,
                AdminNotificationPeriod.TODAY, null);

        assertThat(summary.total()).isZero();
        assertThat(summary.delivered()).isZero();
    }

    @Test
    @DisplayName("목록: 카테고리를 알림 종류 목록으로 풀어 넘기고, 검색어는 사용자 ID로 바꿔 넘긴다")
    @SuppressWarnings("unchecked")
    void 목록_필터_변환() {
        when(userRepository.findIdsByNameContaining("김영")).thenReturn(List.of(WARD_ID));
        when(notificationLogRepository.searchForAdmin(any(), anyBoolean(), anyCollection(), any(), anyBoolean(),
                anyCollection(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getLogs(AdminNotificationCategory.MEDICATION, NotificationLogResult.FAILED,
                AdminNotificationPeriod.LAST_30_DAYS, " 김영 ", 0, 20);

        ArgumentCaptor<Collection<NotificationType>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<String>> userIds = ArgumentCaptor.forClass(Collection.class);
        verify(notificationLogRepository).searchForAdmin(any(OffsetDateTime.class), eq(true), types.capture(),
                eq(NotificationLogResult.FAILED), eq(true), userIds.capture(), any());
        assertThat(types.getValue()).containsExactlyInAnyOrder(NotificationType.MEDICATION_REMINDER,
                NotificationType.MEDICATION_MISSED, NotificationType.MEDICATION_STOPPED);
        assertThat(userIds.getValue()).containsExactly(WARD_ID);
    }

    @Test
    @DisplayName("목록: 검색 결과가 없으면 매칭 불가능한 값으로 좁힌다 - 전체가 나오면 안 된다")
    @SuppressWarnings("unchecked")
    void 목록_검색결과없음() {
        when(userRepository.findIdsByNameContaining("없는이름")).thenReturn(List.of());
        when(notificationLogRepository.searchForAdmin(any(), anyBoolean(), anyCollection(), any(), anyBoolean(),
                anyCollection(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getLogs(null, null, null, "없는이름", 0, 20);

        ArgumentCaptor<Collection<String>> userIds = ArgumentCaptor.forClass(Collection.class);
        verify(notificationLogRepository).searchForAdmin(any(), eq(false), anyCollection(), any(), eq(true),
                userIds.capture(), any());
        assertThat(userIds.getValue()).containsExactly("");
    }

    @Test
    @DisplayName("목록: 채널 칸은 전송 완료 채널만, 팝업용 결과에는 실패 사유 문구가 함께 온다")
    void 목록_항목_매핑() {
        NotificationLog sos = log(1L, GUARDIAN_ID, WARD_ID, NotificationType.WARD_SOS, NotificationLogResult.SMS_FALLBACK,
                null, List.of(
                        new ChannelAttempt(NotificationChannelType.FCM, ChannelResult.Status.FAILED, ChannelFailureReason.NO_DEVICE),
                        new ChannelAttempt(NotificationChannelType.SMS, ChannelResult.Status.DELIVERED, null)));
        givenPage(sos);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(GUARDIAN_ID, "김미정", Role.GUARDIAN), user(WARD_ID, "김영자", Role.WARD)));
        when(connectionRepository.findByGuardianIdInAndWardIdInAndStatus(any(), any(), eq(ConnectionStatus.ACTIVE)))
                .thenReturn(List.of(Connection.builder().guardianId(GUARDIAN_ID).wardId(WARD_ID)
                        .relation("딸").status(ConnectionStatus.ACTIVE).build()));

        PageResponse<AdminNotificationItem> page = service.getLogs(null, null, null, null, 0, 20);

        AdminNotificationItem item = page.content().getFirst();
        assertThat(item.typeLabel()).isEqualTo("SOS");
        assertThat(item.category()).isEqualTo(AdminNotificationCategory.SOS);
        assertThat(item.wardName()).isEqualTo("김영자");
        assertThat(item.recipientName()).isEqualTo("김미정");
        assertThat(item.recipientRole()).isEqualTo(Role.GUARDIAN);
        assertThat(item.recipientIsWard()).isFalse();
        assertThat(item.relation()).isEqualTo("딸");
        assertThat(item.deliveredChannels()).containsExactly(NotificationChannelType.SMS);
        assertThat(item.channelResults()).extracting(AdminNotificationItem.ChannelResultItem::reasonLabel)
                .containsExactly("등록된 기기 없음", null);
    }

    @Test
    @DisplayName("목록: 본인이 받은 알림은 '본인'이고 관계 라벨을 찾지 않는다")
    void 목록_본인수신() {
        NotificationLog self = log(2L, WARD_ID, WARD_ID, NotificationType.MEDICATION_REMINDER,
                NotificationLogResult.FAILED, null, List.of(new ChannelAttempt(
                        NotificationChannelType.FCM, ChannelResult.Status.FAILED, ChannelFailureReason.NO_DEVICE)));
        givenPage(self);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(WARD_ID, "임하준", Role.WARD)));

        AdminNotificationItem item = service.getLogs(null, null, null, null, 0, 20).content().getFirst();

        assertThat(item.recipientIsWard()).isTrue();
        assertThat(item.relation()).isNull();
        assertThat(item.deliveredChannels()).isEmpty();
        verify(connectionRepository, never()).findByGuardianIdInAndWardIdInAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("목록: 보내지 않은 건은 사유 문구가 오고 채널 결과는 빈 배열이다")
    void 목록_보내지않음() {
        NotificationLog blocked = log(3L, GUARDIAN_ID, WARD_ID, NotificationType.WARD_SOS,
                NotificationLogResult.NOT_SENT, NotificationNotSentReason.RESTRICTED_ACCOUNT, List.of());
        givenPage(blocked);
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(connectionRepository.findByGuardianIdInAndWardIdInAndStatus(any(), any(), any())).thenReturn(List.of());

        AdminNotificationItem item = service.getLogs(null, null, null, null, 0, 20).content().getFirst();

        assertThat(item.notSentReasonLabel()).isEqualTo("이용 제한 계정");
        assertThat(item.channelResults()).isEmpty();
        assertThat(item.deliveredChannels()).isEmpty();
    }

    private void givenPage(NotificationLog... logs) {
        when(notificationLogRepository.searchForAdmin(any(), anyBoolean(), anyCollection(), any(), anyBoolean(),
                anyCollection(), any())).thenReturn(new PageImpl<>(List.of(logs), PageRequest.of(0, 20), logs.length));
    }

    private static NotificationLog log(Long id, String recipientId, String wardId, NotificationType type,
                                       NotificationLogResult result, NotificationNotSentReason reason,
                                       List<ChannelAttempt> attempts) {
        NotificationLog log = NotificationLog.builder()
                .createdAt(OffsetDateTime.now()).type(type).recipientId(recipientId).wardId(wardId)
                .title("제목").body("본문").result(result).notSentReason(reason).channelResults(attempts)
                .build();
        ReflectionTestUtils.setField(log, "id", id);
        return log;
    }

    private static User user(String id, String name, Role role) {
        return User.builder()
                .id(id).email(id.toLowerCase() + "@example.com").password("encoded")
                .name(name).role(role).status(Status.ACTIVE).provider(Provider.LOCAL)
                .build();
    }

    private static NotificationLogRepository.ResultCount count(NotificationLogResult result, long total) {
        return new NotificationLogRepository.ResultCount() {
            @Override
            public NotificationLogResult getResult() {
                return result;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }
}
