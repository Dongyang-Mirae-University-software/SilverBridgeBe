package kr.silverbridge.main.domain.checkin;

import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.domain.checkin.service.CheckinSessionStore;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 안부 보호자 알림 전체 경로(2026-11-01 점검 M-2): 저장소 쓰기 → 커밋 → AFTER_COMMIT 리스너(SpEL 조건) → executor →
 * 선점(REQUIRES_NEW) → 디스패처 → 알림 이력. 말벗 킬 스위치와 무관한 경로라 기반 클래스 설정(말벗 꺼짐) 그대로 돈다.
 */
class CheckinGuardianAlertFullIntegrationTest extends FullContextIntegrationTest {

    private static final String WARD = "WCK001";
    private static final String GUARDIAN = "GCK001";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final OffsetDateTime AT = OffsetDateTime.of(DAY.atTime(15, 5), ZoneOffset.ofHours(9));

    @Autowired private CheckinSessionStore store;
    @Autowired private CheckinSessionRepository sessionRepository;

    @Test
    @DisplayName("턴에서 위험이 처음 올라가면 커밋 뒤 긴급 executor에서 보호자에게 CHECKIN_RISK 한 번(다음 위험 턴은 다시 안 보냄)")
    void riskOnTurn() {
        Long sessionPk = givenSession();
        AtomicReference<String> thread = new AtomicReference<>();
        doAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(notificationDispatcher).dispatch(eq(GUARDIAN), eq(WARD), eq(NotificationType.CHECKIN_RISK),
                any(NotificationContent.class));

        store.recordTurn(sessionPk, 0, false, false, true);
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.CHECKIN_RISK)).isEqualTo(1));
        assertThat(thread.get()).startsWith("notify-urgent-");

        store.recordTurn(sessionPk, 1, false, true, false);
        awaitAsyncIdle();
        assertThat(notificationLogs(GUARDIAN, NotificationType.CHECKIN_RISK)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM checkin_guardian_alert_log WHERE guardian_id = ? AND kind = 'RISK'",
                GUARDIAN)).isEqualTo(1);
    }

    @Test
    @DisplayName("위험 없는 정리에서 확인 필요면 일반 executor로 CHECKIN_ATTENTION, 위험 알림은 없음")
    void attentionOnFinish() {
        Long sessionPk = givenSession();

        store.finish(sessionPk, result(sessionPk, true), CheckinEndReason.USER_END, AT);

        eventually().untilAsserted(() ->
                assertThat(notificationLogs(GUARDIAN, NotificationType.CHECKIN_ATTENTION)).isEqualTo(1));
        assertThat(notificationLogs(GUARDIAN, NotificationType.CHECKIN_RISK)).isZero();
    }

    @Test
    @DisplayName("확인 필요가 아니면 보호자 알림 없음")
    void calmFinishSendsNothing() {
        Long sessionPk = givenSession();

        store.finish(sessionPk, result(sessionPk, false), CheckinEndReason.USER_END, AT);

        awaitAsyncIdle();
        assertThat(notificationLogs(GUARDIAN, NotificationType.CHECKIN_ATTENTION)).isZero();
        org.mockito.Mockito.verify(notificationDispatcher, org.mockito.Mockito.never())
                .dispatch(anyString(), anyString(), eq(NotificationType.CHECKIN_ATTENTION), any());
    }

    private Long givenSession() {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);
        return sessionRepository.saveAndFlush(CheckinSession.start("ck_fullintegrationtest0001", WARD, DAY,
                CheckinSlot.AT_15, "질문", AT)).getId();
    }

    private static CheckinResult result(Long sessionPk, boolean attention) {
        return CheckinResult.builder().sessionPk(sessionPk).wardId(WARD).checkDate(DAY).slot(CheckinSlot.AT_15)
                .emotion(CheckinEmotion.WORRIED).evidence(List.of()).endReason(CheckinEndReason.USER_END).createdAt(AT)
                .attention(attention).attentionReasons(attention ? List.of("SLEEP_UNUSUAL", "MEAL_UNUSUAL") : List.of())
                .build();
    }
}
