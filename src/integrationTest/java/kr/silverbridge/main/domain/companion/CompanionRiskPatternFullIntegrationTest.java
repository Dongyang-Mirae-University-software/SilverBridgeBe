package kr.silverbridge.main.domain.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskPatternCheckEvent;
import kr.silverbridge.main.domain.companion.listener.CompanionRiskPatternListener;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 여러 날 위험 누적 알림(2026-11-11) - 턴 → 커밋 → 긴급 리스너 → 디스패처 → 이력까지 실제 빈·실 DB로 본다.
 * 7일 1번 선점(조건부 UPSERT)의 동시 경합도 실 DB로 확인한다. AI는 목이다.
 *
 * <p>스위치는 기반 클래스 속성을 늘리지 않으려고(컨텍스트 재기동) 실행 중 빈 값을 켜고 끝나면 되돌린다.</p>
 */
class CompanionRiskPatternFullIntegrationTest extends FullContextIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADMIN = "ARP001";
    private static final String GUARDIAN = "GRP001";
    private static final String WARD = "WRP001";

    @Autowired private CompanionService companionService;
    @Autowired private CompanionSessionRepository sessionRepository;
    @Autowired private CompanionProperties companionProperties;
    @Autowired private CompanionRiskPatternListener riskPatternListener;
    @Autowired private AdminUserService adminUserService;

    @BeforeEach
    void enable() {
        companionProperties.getRiskPattern().setEnabled(true);
    }

    @AfterEach
    void disable() {
        companionProperties.getRiskPattern().setEnabled(false);
    }

    @Test
    @DisplayName("지난 높은 날 + 오늘 0.97 이상 턴 → COMPANION_RISK_PATTERN 1건(위험 알림 없음), 같은 날 두 번째 높은 턴은 더 보내지 않는다")
    void 두_날이면_누적_알림_한_번() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        LocalDate today = LocalDate.now(KST);
        savePastHigh(today.minusDays(3), "0.9810");
        long sessionId = openSession("d-pattern-1");
        when(aiCompanionClient.replyText(eq("d-pattern-1"), anyString())).thenReturn(
                JSON.readTree("{\"turnNo\":1,\"reply\":{\"text\":\"그러셨군요.\"},\"riskFlag\":false,"
                        + "\"riskProbability\":0.9751,\"ended\":false}"),
                JSON.readTree("{\"turnNo\":2,\"reply\":{\"text\":\"그러셨군요.\"},\"riskFlag\":false,"
                        + "\"riskProbability\":0.9902,\"ended\":false}"));

        companionService.replyText(WARD, sessionId, "대답");
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK_PATTERN))
                .as("두 날째 높은 턴에 1번").isEqualTo(1));

        companionService.replyText(WARD, sessionId, "대답");
        awaitAsyncIdle();

        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK_PATTERN)).as("7일에 1번").isEqualTo(1);
        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK)).as("riskFlag 알림은 별개").isZero();
        assertThat(jdbc.queryForObject("SELECT max_risk_prob FROM companion_session WHERE id = ?", BigDecimal.class,
                sessionId)).as("대화 최댓값").isEqualByComparingTo("0.9902");
        assertThat(count("SELECT count(*) FROM companion_risk_pattern_notice WHERE ward_id = ? AND days = 2"
                + " AND last_notified_date = ?", WARD, today)).isEqualTo(1);

        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        adminUserService.updateUser(WARD, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN);
        assertThat(count("SELECT count(*) FROM companion_risk_pattern_notice WHERE ward_id = ?", WARD))
                .as("역할 변경 정리에 포함").isZero();
    }

    @Test
    @DisplayName("같은 피보호자 판정이 동시에 여러 번 돌아도 선점(조건부 UPSERT)에 이긴 한 번만 보낸다")
    void 동시_판정도_한_번() {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        LocalDate today = LocalDate.now(KST);
        savePastHigh(today.minusDays(1), "0.98");
        savePastHigh(today, "0.98");

        CompanionRiskPatternCheckEvent event = new CompanionRiskPatternCheckEvent(WARD, today);
        for (int i = 0; i < 6; i++) {
            riskPatternListener.handleCheck(event); // @Async - 긴급 풀에서 동시에 돈다
        }
        awaitAsyncIdle();

        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK_PATTERN)).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 발송이 7일 전이면 다시 보내고, 6일 전이면 보내지 않는다")
    void 일곱_날_뒤에는_다시() {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        LocalDate today = LocalDate.now(KST);
        savePastHigh(today.minusDays(1), "0.98");
        savePastHigh(today, "0.98");
        CompanionRiskPatternCheckEvent event = new CompanionRiskPatternCheckEvent(WARD, today);

        jdbc.update("INSERT INTO companion_risk_pattern_notice (ward_id, last_notified_date, days, sent_at)"
                + " VALUES (?, ?, 2, now())", WARD, today.minusDays(6));
        riskPatternListener.handleCheck(event);
        awaitAsyncIdle();
        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK_PATTERN)).isZero();

        jdbc.update("UPDATE companion_risk_pattern_notice SET last_notified_date = ? WHERE ward_id = ?",
                today.minusDays(7), WARD);
        riskPatternListener.handleCheck(event);
        awaitAsyncIdle();
        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK_PATTERN)).isEqualTo(1);
    }

    /** 높은 확률 대화(연결 이후로 보이게 시작 시각은 지금). 슬롯은 날짜마다 다르게 둔다. */
    private void savePastHigh(LocalDate date, String probability) {
        CompanionSlot slot = CompanionSlot.S21;
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES (?, '시험 질문') RETURNING id", Long.class, slot.code());
        OffsetDateTime now = OffsetDateTime.now(KST);
        jdbc.update("INSERT INTO companion_session (ward_id, session_date, slot, question_id, dialogue_id, status, "
                        + "end_reason, turn_count, answered_count, no_response_count, max_risk_prob, started_at, "
                        + "last_activity_at, ended_at) VALUES (?, ?, ?, ?, ?, 'FINISHED', 'MAX_TURNS', 8, 8, 0, ?, ?, ?, ?)",
                WARD, date, slot.code(), questionId, "d-high-" + date, new BigDecimal(probability), now, now, now);
    }

    private long openSession(String dialogueId) {
        OffsetDateTime now = OffsetDateTime.now(KST);
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES (?, '시험 질문') RETURNING id", Long.class,
                CompanionSlot.S09.code());
        CompanionSession session = CompanionSession.claim(WARD, LocalDate.now(KST), CompanionSlot.S09, questionId, now);
        session.attachDialogue(dialogueId, 8, now);
        return sessionRepository.save(session).getId();
    }
}
