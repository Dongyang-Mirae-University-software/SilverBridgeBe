package kr.silverbridge.main.domain.notification;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.UserNotificationCategory;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 내 알림 기록(V67 read_at) - 실제 PostgreSQL에서만 검증되는 것들.
 *
 * <p>보이는 행(NOT_SENT 제외·90일 하한·본인만), 묶음 필터, 페이지 경계, 읽음 UPDATE의 멱등·처음 시각 유지,
 * 모두 읽음 뒤 안 읽은 수 0, 부분 인덱스 존재.</p>
 */
class UserNotificationInboxIntegrationTest extends PostgresIntegrationTest {

    private static final String GUARDIAN_ID = "GD0001";
    private static final String OTHER_GUARDIAN_ID = "GD0002";
    private static final String WARD_ID = "WD0001";
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.ofHours(9));
    private static final OffsetDateTime FROM = NOW.minusDays(90);
    private static final EnumSet<NotificationType> ALL = EnumSet.allOf(NotificationType.class);

    @Autowired private UserRepository userRepository;
    @Autowired private NotificationLogRepository repository;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.save(TestData.user(GUARDIAN_ID, "김미정", Role.GUARDIAN));
        userRepository.save(TestData.user(OTHER_GUARDIAN_ID, "박보호", Role.GUARDIAN));
        userRepository.save(TestData.user(WARD_ID, "김영자", Role.WARD));
    }

    @Test
    @DisplayName("목록·안 읽은 수: 본인 것만, NOT_SENT 제외, 90일 밖 제외, DELIVERED·SMS_FALLBACK·FAILED 포함, 최신순")
    void 보이는_행() {
        NotificationLog delivered = save(GUARDIAN_ID, NotificationType.ANOMALY_DETECTED, NotificationLogResult.DELIVERED, NOW.minusHours(1));
        NotificationLog fallback = save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.SMS_FALLBACK, NOW.minusHours(2));
        NotificationLog failed = save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.FAILED, NOW.minusHours(3));
        save(GUARDIAN_ID, NotificationType.MEDICATION_MISSED, NotificationLogResult.NOT_SENT, NOW);
        save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW.minusDays(91));
        save(OTHER_GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        flushClear();

        Page<NotificationLog> page = repository.searchForRecipient(GUARDIAN_ID, FROM, false, ALL, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(NotificationLog::getId)
                .containsExactly(delivered.getId(), fallback.getId(), failed.getId());
        assertThat(repository.countUnread(GUARDIAN_ID, FROM)).isEqualTo(3);
    }

    @Test
    @DisplayName("묶음 필터와 페이지 경계 (같은 시각은 id 내림차순)")
    void 묶음과_페이지() {
        for (int i = 0; i < 3; i++) {
            save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW.minusMinutes(i));
        }
        save(GUARDIAN_ID, NotificationType.MEDICATION_MISSED, NotificationLogResult.DELIVERED, NOW);
        save(GUARDIAN_ID, NotificationType.CONNECTION_ACCEPTED, NotificationLogResult.DELIVERED, NOW);
        flushClear();

        Page<NotificationLog> urgent = repository.searchForRecipient(GUARDIAN_ID, FROM, true,
                UserNotificationCategory.URGENT.types(), PageRequest.of(0, 2));
        Page<NotificationLog> urgentLast = repository.searchForRecipient(GUARDIAN_ID, FROM, true,
                UserNotificationCategory.URGENT.types(), PageRequest.of(1, 2));
        Page<NotificationLog> medication = repository.searchForRecipient(GUARDIAN_ID, FROM, true,
                UserNotificationCategory.MEDICATION.types(), PageRequest.of(0, 20));

        assertThat(urgent.getTotalElements()).isEqualTo(3);
        assertThat(urgent.getContent()).hasSize(2);
        assertThat(urgentLast.getContent()).hasSize(1);
        assertThat(medication.getContent()).extracting(NotificationLog::getType)
                .containsExactly(NotificationType.MEDICATION_MISSED);
    }

    @Test
    @DisplayName("읽음: 본인 것만 바뀌고, 두 번 불러도 처음 읽은 시각이 유지된다 / 남의 것·NOT_SENT는 0")
    void 읽음_멱등() {
        NotificationLog mine = save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        NotificationLog notSent = save(GUARDIAN_ID, NotificationType.MEDICATION_MISSED, NotificationLogResult.NOT_SENT, NOW);
        NotificationLog others = save(OTHER_GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        flushClear();

        OffsetDateTime first = NOW.minusMinutes(5);
        assertThat(repository.markRead(mine.getId(), GUARDIAN_ID, FROM, first)).isEqualTo(1);
        assertThat(repository.markRead(mine.getId(), GUARDIAN_ID, FROM, NOW)).isEqualTo(1);
        assertThat(repository.markRead(notSent.getId(), GUARDIAN_ID, FROM, NOW)).isZero();
        assertThat(repository.markRead(others.getId(), GUARDIAN_ID, FROM, NOW)).isZero();

        // DB는 마이크로초까지 저장한다 - 두 번째 호출 시각(5분 뒤)이 아니라 처음 시각이어야 한다.
        assertThat(repository.findById(mine.getId()).orElseThrow().getReadAt().toInstant())
                .isCloseTo(first.toInstant(), within(1, ChronoUnit.MILLIS));
        assertThat(repository.findById(others.getId()).orElseThrow().getReadAt()).isNull();
        assertThat(repository.findRecipientIdById(others.getId())).contains(OTHER_GUARDIAN_ID);
        assertThat(repository.findRecipientIdById(-1L)).isEmpty();
    }

    @Test
    @DisplayName("모두 읽음: 본인의 안 읽은 보이는 알림만 바뀌고 안 읽은 수가 0이 된다")
    void 모두_읽음() {
        save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        save(GUARDIAN_ID, NotificationType.CAMERA_LINK_LOST, NotificationLogResult.FAILED, NOW);
        save(GUARDIAN_ID, NotificationType.MEDICATION_MISSED, NotificationLogResult.NOT_SENT, NOW);
        save(OTHER_GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        flushClear();

        assertThat(repository.markAllRead(GUARDIAN_ID, FROM, NOW)).isEqualTo(2);
        assertThat(repository.markAllRead(GUARDIAN_ID, FROM, NOW)).isZero();
        assertThat(repository.countUnread(GUARDIAN_ID, FROM)).isZero();
        assertThat(repository.countUnread(OTHER_GUARDIAN_ID, FROM)).isEqualTo(1);
    }

    @Test
    @DisplayName("보관 기간(90일) 밖 행은 읽음·모두 읽음 대상이 아니다 - 목록·안 읽은 수와 같은 범위 (점검 L-2)")
    void 보관기간_밖_읽음_제외() {
        NotificationLog old = save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW.minusDays(91));
        save(GUARDIAN_ID, NotificationType.WARD_SOS, NotificationLogResult.DELIVERED, NOW);
        flushClear();

        assertThat(repository.markRead(old.getId(), GUARDIAN_ID, FROM, NOW)).isZero();
        assertThat(repository.countUnread(GUARDIAN_ID, FROM)).isEqualTo(1);
        assertThat(repository.markAllRead(GUARDIAN_ID, FROM, NOW)).isEqualTo(1);
        assertThat(repository.findById(old.getId()).orElseThrow().getReadAt()).isNull();
    }

    @Test
    @DisplayName("V67 부분 인덱스가 있고 조건이 쿼리 리터럴과 같다")
    @SuppressWarnings("unchecked")
    void 부분_인덱스() {
        List<String> defs = entityManager.createNativeQuery("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'notification_log'
                  AND indexname IN ('idx_notification_log_recipient_visible', 'idx_notification_log_recipient_unread')
                """).getResultList();
        assertThat(defs).hasSize(2);
        assertThat(defs).allSatisfy(def -> assertThat(def).contains("'NOT_SENT'"));
        assertThat(defs).anySatisfy(def -> assertThat(def).contains("read_at IS NULL"));
    }

    private NotificationLog save(String recipientId, NotificationType type, NotificationLogResult result,
                                 OffsetDateTime createdAt) {
        return repository.save(NotificationLog.builder()
                .createdAt(createdAt).type(type).recipientId(recipientId).wardId(WARD_ID)
                .title("제목").body("본문")
                .result(result)
                .notSentReason(result == NotificationLogResult.NOT_SENT ? NotificationNotSentReason.NO_ENABLED_CHANNEL : null)
                .build());
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
