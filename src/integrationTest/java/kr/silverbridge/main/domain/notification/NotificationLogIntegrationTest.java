package kr.silverbridge.main.domain.notification;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationCategory;
import kr.silverbridge.main.domain.notification.entity.ChannelAttempt;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.notification.entity.NotificationNotSentReason;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 알림 이력(notification_log, V54) - 실제 PostgreSQL에서만 검증되는 것들.
 *
 * <p>채널 결과 JSONB 매핑, 관리자 목록·요약 JPQL(빈 IN 절 회피 플래그), CHECK 제약, 탈퇴 CASCADE, 보관 정리.</p>
 */
class NotificationLogIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";
    private static final List<String> NO_MATCH = List.of("");
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.ofHours(9));
    private static final OffsetDateTime WEEK_AGO = NOW.minusDays(7);

    @Autowired private UserRepository userRepository;
    @Autowired private NotificationLogRepository notificationLogRepository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "김미정", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "김영자", Role.WARD));
    }

    @Test
    @DisplayName("채널별 결과(JSONB)가 enum 그대로 저장되고 다시 읽힌다")
    void jsonb_왕복() {
        NotificationLog saved = notificationLogRepository.save(log(NotificationType.WARD_SOS,
                NotificationLogResult.SMS_FALLBACK, null, NOW, List.of(
                        new ChannelAttempt(NotificationChannelType.FCM, ChannelResult.Status.FAILED, ChannelFailureReason.NO_DEVICE),
                        new ChannelAttempt(NotificationChannelType.SMS, ChannelResult.Status.DELIVERED, null))));
        entityManager.flush();
        entityManager.clear();

        NotificationLog found = notificationLogRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getChannelResults()).containsExactly(
                new ChannelAttempt(NotificationChannelType.FCM, ChannelResult.Status.FAILED, ChannelFailureReason.NO_DEVICE),
                new ChannelAttempt(NotificationChannelType.SMS, ChannelResult.Status.DELIVERED, null));
        assertThat(found.getWardId()).isEqualTo(WARD_ID);
        assertThat(found.getType()).isEqualTo(NotificationType.WARD_SOS);
    }

    @Test
    @DisplayName("목록: 기간·카테고리·결과·이름 검색으로 좁히고 최신순이다")
    void 목록_필터() {
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, null, NOW.minusHours(1), List.of()));
        notificationLogRepository.save(log(NotificationType.MEDICATION_REMINDER, NotificationLogResult.FAILED, null, NOW, List.of()));
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.FAILED, null, NOW.minusDays(10), List.of()));
        entityManager.flush();

        // 필터 없음(기간만) - 10일 전 건은 빠진다, 최신순
        assertThat(search(false, EnumSet.allOf(NotificationType.class), null, false, NO_MATCH))
                .extracting(NotificationLog::getType)
                .containsExactly(NotificationType.MEDICATION_REMINDER, NotificationType.WARD_SOS);
        // 카테고리
        assertThat(search(true, AdminNotificationCategory.SOS.types(), null, false, NO_MATCH))
                .extracting(NotificationLog::getType).containsExactly(NotificationType.WARD_SOS);
        // 결과("전송 실패" 탭)
        assertThat(search(false, EnumSet.allOf(NotificationType.class), NotificationLogResult.FAILED, false, NO_MATCH))
                .extracting(NotificationLog::getType).containsExactly(NotificationType.MEDICATION_REMINDER);
        // 이름 검색 - 매칭 없음이면 0건(전체가 나오면 안 된다)
        assertThat(search(false, EnumSet.allOf(NotificationType.class), null, true, NO_MATCH)).isEmpty();
        // 이름 검색 - 피보호자로도 수신자로도 걸린다
        assertThat(search(false, EnumSet.allOf(NotificationType.class), null, true, List.of(WARD_ID))).hasSize(2);
        assertThat(search(false, EnumSet.allOf(NotificationType.class), null, true, List.of(GUARDIAN_ID))).hasSize(2);
    }

    @Test
    @DisplayName("요약: 결과별 건수를 돌려준다(목록과 같은 조건)")
    void 요약_결과별() {
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, null, NOW, List.of()));
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.SMS_FALLBACK, null, NOW, List.of()));
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.NOT_SENT,
                NotificationNotSentReason.RESTRICTED_ACCOUNT, NOW, List.of()));
        notificationLogRepository.save(log(NotificationType.INQUIRY_ANSWERED, NotificationLogResult.FAILED, null, NOW, List.of()));
        entityManager.flush();

        Map<NotificationLogResult, Long> sos = notificationLogRepository.countByResult(WEEK_AGO, true,
                        AdminNotificationCategory.SOS.types(), false, NO_MATCH).stream()
                .collect(Collectors.toMap(NotificationLogRepository.ResultCount::getResult,
                        NotificationLogRepository.ResultCount::getTotal));

        assertThat(sos).containsOnly(
                Map.entry(NotificationLogResult.DELIVERED, 1L),
                Map.entry(NotificationLogResult.SMS_FALLBACK, 1L),
                Map.entry(NotificationLogResult.NOT_SENT, 1L));
    }

    // CHECK 위반은 테스트마다 한 번만 부른다 - PostgreSQL은 오류 뒤 같은 트랜잭션의 문장을 전부 거부해,
    // 두 번째 호출은 CHECK와 무관하게 실패하므로 검증이 거짓으로 통과한다.

    @Test
    @DisplayName("CHECK 대조군: 올바른 짝(보내지 않음 + 사유)은 들어간다")
    void check_정상짝() {
        insertRaw("NOT_SENT", "NO_ENABLED_CHANNEL");

        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("CHECK: 보내지 않음인데 사유가 없으면 거부된다")
    void check_보내지않음_사유없음() {
        assertThatThrownBy(() -> insertRaw("NOT_SENT", null)).hasStackTraceContaining("chk_notification_log_not_sent_pair");
    }

    @Test
    @DisplayName("CHECK: 보낸 건에 보내지 않은 사유가 붙으면 거부된다")
    void check_보냄인데_사유있음() {
        assertThatThrownBy(() -> insertRaw("DELIVERED", "RESTRICTED_ACCOUNT"))
                .hasStackTraceContaining("chk_notification_log_not_sent_pair");
    }

    @Test
    @DisplayName("CHECK: 모르는 결과 값은 거부된다")
    void check_결과값() {
        // 사유 null이라 짝 CHECK는 통과한다 - 결과 CHECK만 위반된다
        assertThatThrownBy(() -> insertRaw("MAYBE", null))
                .hasStackTraceContaining("chk_notification_log_result");
    }

    @Test
    @DisplayName("수신자가 탈퇴(행 삭제)하면 그 이력도 함께 지워진다(CASCADE)")
    void 탈퇴_cascade() {
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, null, NOW, List.of()));
        entityManager.flush();
        entityManager.clear();

        entityManager.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", GUARDIAN_ID).executeUpdate();

        assertThat(notificationLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("보관 정리: 기준 시각보다 오래된 이력만 지운다")
    void 보관정리() {
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, null, NOW.minusDays(91), List.of()));
        notificationLogRepository.save(log(NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, null, NOW.minusDays(89), List.of()));
        entityManager.flush();

        int deleted = notificationLogRepository.deleteOlderThan(NOW.minusDays(90));

        assertThat(deleted).isEqualTo(1);
        assertThat(notificationLogRepository.count()).isEqualTo(1);
    }

    private List<NotificationLog> search(boolean typesApplied, java.util.Collection<NotificationType> types,
                                         NotificationLogResult result, boolean keywordApplied, List<String> userIds) {
        return notificationLogRepository.searchForAdmin(WEEK_AGO, typesApplied, types, result, keywordApplied,
                userIds, PageRequest.of(0, 20)).getContent();
    }

    private void insertRaw(String result, String notSentReason) {
        entityManager.createNativeQuery("""
                        INSERT INTO notification_log (created_at, type, recipient_id, result, not_sent_reason)
                        VALUES (now(), 'WARD_SOS', :recipient, :result, :reason)
                        """)
                .setParameter("recipient", GUARDIAN_ID)
                .setParameter("result", result)
                .setParameter("reason", notSentReason)
                .executeUpdate();
    }

    private static NotificationLog log(NotificationType type, NotificationLogResult result,
                                       NotificationNotSentReason reason, OffsetDateTime createdAt,
                                       List<ChannelAttempt> attempts) {
        return NotificationLog.builder()
                .createdAt(createdAt).type(type).recipientId(GUARDIAN_ID).wardId(WARD_ID)
                .title("긴급 SOS").body("김영자님이 긴급 도움을 요청했습니다.")
                .result(result).notSentReason(reason).channelResults(attempts)
                .build();
    }
}
