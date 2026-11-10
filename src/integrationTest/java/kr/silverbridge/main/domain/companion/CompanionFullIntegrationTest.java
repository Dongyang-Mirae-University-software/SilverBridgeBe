package kr.silverbridge.main.domain.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 안부 말벗 - 서비스 → 커밋 → 리스너 → 디스패처까지 실제 빈 구성으로 본다(2026-11-04 점검 M-2·M-1).
 *
 * <p>위험 알림 1회 보장은 {@code risk_notified_at} 조건부 UPDATE와 BEFORE/AFTER_COMMIT 이벤트가 맞물려야 성립한다.
 * 단위 테스트는 writer를 목으로 두어 이 연결을 보지 못했다(결함 주입 2종 미검출). AI는 목이다.</p>
 */
class CompanionFullIntegrationTest extends FullContextIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "ACM001";
    private static final String GUARDIAN = "GCM001";
    private static final String WARD = "WCM001";

    @Autowired private CompanionService companionService;
    @Autowired private CompanionSessionRepository sessionRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private UserService userService;
    @Autowired private kr.silverbridge.main.domain.companion.service.CompanionGuardianService companionGuardianService;
    @Autowired private kr.silverbridge.main.domain.companion.service.CompanionStatsService companionStatsService;

    @Test
    @DisplayName("위험 신호 턴 → 보호자에게 COMPANION_RISK 1번, 같은 대화의 두 번째 위험 턴과 URGENT 마감은 더 보내지 않는다")
    void 위험_알림은_대화당_한_번() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);
        long sessionId = openSession("d-risk-1");
        when(aiCompanionClient.replyText(eq("d-risk-1"), anyString())).thenReturn(json(
                "{\"turnNo\":1,\"reply\":{\"text\":\"많이 힘드셨군요.\"},\"signals\":{\"riskFlag\":true},\"ended\":false}"));
        when(aiCompanionClient.finish("d-risk-1")).thenReturn(json(
                "{\"dialogueId\":\"d-risk-1\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"mood\":\"우울\","
                        + "\"riskFlag\":true,\"alertLevel\":\"URGENT\"}"));

        companionService.replyText(WARD, sessionId, "요즘 너무 힘들어요");
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK))
                .as("첫 위험 신호 턴에 1번").isEqualTo(1));

        companionService.replyText(WARD, sessionId, "그냥 다 힘들어요");
        companionService.finish(WARD, sessionId);
        awaitAsyncIdle();

        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK)).as("대화당 1번").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT alert_level FROM companion_session WHERE id = ?", String.class, sessionId))
                .isEqualTo("URGENT");
        assertThat(count("SELECT count(*) FROM companion_session WHERE id = ? AND risk_notified_at IS NOT NULL",
                sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("확인(되묻기) 턴은 signals가 null이고 최상위 riskFlag에만 위험을 싣는다 - 그 턴에 COMPANION_RISK 1번")
    void 확인_턴_위험도_그_턴에_보낸다() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);
        long sessionId = openSession("d-risk-confirm");
        when(aiCompanionClient.replyText(eq("d-risk-confirm"), anyString())).thenReturn(json(
                "{\"turnNo\":1,\"reply\":{\"text\":\"조금 더 말씀해 주실래요?\"},\"replySource\":\"CONFIRM\","
                        + "\"needsCheck\":true,\"signals\":null,\"riskFlag\":true,\"ended\":false}"));

        companionService.replyText(WARD, sessionId, "다 그만두고 싶어요");
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK))
                .as("마감을 기다리지 않고 확인 턴에 1번").isEqualTo(1));

        assertThat(count("SELECT count(*) FROM companion_session WHERE id = ? AND status = 'OPEN'"
                + " AND risk_flag = true AND risk_notified_at IS NOT NULL", sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("D-1: 주의 알림은 보호자마다 연결 이후 대화로 센다 - 오래 연결한 보호자만 3일째를 받고, 오늘 연결한 보호자는 받지 않는다")
    void 주의_알림은_연결_이후_대화로_센다() throws Exception {
        String newGuardian = "GCN002";
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(newGuardian, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        Long oldConnection = saveActiveConnection(GUARDIAN, WARD);
        Long newConnection = saveActiveConnection(newGuardian, WARD);
        jdbc.update("UPDATE connection SET connected_at = now() - interval '10 days' WHERE id = ?", oldConnection);
        jdbc.update("UPDATE connection SET connected_at = now() - interval '1 minute' WHERE id = ?", newConnection);
        signedIn(GUARDIAN);
        signedIn(newGuardian);
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        saveFinished(today.minusDays(2), CompanionSlot.S21, "WORRY",
                today.minusDays(2).atTime(21, 1).atZone(kst).toOffsetDateTime());
        saveFinished(today.minusDays(1), CompanionSlot.S21, "DEPRESSED",
                today.minusDays(1).atTime(21, 1).atZone(kst).toOffsetDateTime());
        long sessionId = openSession("d-notice-d1");
        when(aiCompanionClient.finish("d-notice-d1")).thenReturn(json(
                "{\"dialogueId\":\"d-notice-d1\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"answeredCount\":2,"
                        + "\"mood\":\"WORRY\",\"riskFlag\":false,\"alertLevel\":\"INFO\"}"));

        companionService.finish(WARD, sessionId);
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_NOTICE))
                .as("오래 연결한 보호자는 3일째").isEqualTo(1));
        awaitAsyncIdle();

        assertThat(notificationLogs(newGuardian, NotificationType.COMPANION_NOTICE))
                .as("오늘 연결한 보호자에게는 연결 전 기분 일수를 알리지 않는다").isZero();
        assertThat(count("SELECT count(*) FROM companion_notice_log WHERE ward_id = ? AND days = 3", WARD)).isEqualTo(1);
        assertThat(companionGuardianService.today(newGuardian, WARD).attention()).as("새 보호자 화면도 주의 없음").isNull();
    }

    @Test
    @DisplayName("턴에는 위험 신호가 없었어도 마감 결과가 URGENT면 그때 1번 보낸다")
    void 마감_URGENT_미발송이면_보낸다() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);
        long sessionId = openSession("d-risk-2");
        when(aiCompanionClient.finish("d-risk-2")).thenReturn(json(
                "{\"dialogueId\":\"d-risk-2\",\"endReason\":\"USER_STOP\",\"turnCount\":1,\"mood\":null,"
                        + "\"riskFlag\":true,\"alertLevel\":\"URGENT\"}"));

        companionService.finish(WARD, sessionId);

        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK))
                .isEqualTo(1));
    }

    @Test
    @DisplayName("피보호자 → 보호자 역할 변경: 말벗 기록을 지우고 AI 쪽 데이터 삭제를 부른다(점검 M-1)")
    void 역할_변경은_말벗_기록을_정리한다() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        openSession("d-role-1");

        adminUserService.updateUser(WARD, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN);

        assertThat(count("SELECT count(*) FROM companion_session WHERE ward_id = ?", WARD)).isZero();
        eventually().untilAsserted(() -> {
            verify(aiCompanionClient).deleteWardData(WARD);
            assertThat(count("SELECT count(*) FROM companion_purge_request WHERE ward_id = ?", WARD))
                    .as("삭제 성공 뒤 대기열에서 빠진다").isZero();
        });
    }

    @Test
    @DisplayName("피보호자 탈퇴: 대기열에 넣고 커밋 뒤 AI 쪽 데이터 삭제를 부른다")
    void 탈퇴는_AI_삭제를_부른다() {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        openSession("d-wd-1");

        userService.forceWithdraw(WARD, "127.0.0.1", "it");

        eventually().untilAsserted(() -> verify(aiCompanionClient).deleteWardData(WARD));
    }

    @Test
    @DisplayName("PR-B 주의 알림: 걱정·우울 3일째에 켠 보호자에게 하루 1번 - 같은 날 두 번째 걱정 대화는 더 보내지 않는다"
            + "(첫 결과는 새 AI 영문 코드, 두 번째는 옛 AI 한글)")
    void 주의_알림은_하루_한_번() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        saveFinished(today.minusDays(2), CompanionSlot.S21, "WORRY");
        saveFinished(today.minusDays(1), CompanionSlot.S21, "DEPRESSED");
        long first = openSession("d-notice-1");
        when(aiCompanionClient.finish("d-notice-1")).thenReturn(json(
                "{\"dialogueId\":\"d-notice-1\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"answeredCount\":2,"
                        + "\"mood\":\"WORRY\",\"moodCode\":\"WORRY\",\"moodKo\":\"걱정\",\"riskFlag\":false,"
                        + "\"alertLevel\":\"INFO\"}"));

        companionService.finish(WARD, first);
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_NOTICE))
                .isEqualTo(1));

        long second = openSession("d-notice-2", CompanionSlot.S12);
        when(aiCompanionClient.finish("d-notice-2")).thenReturn(json(
                "{\"dialogueId\":\"d-notice-2\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"answeredCount\":2,"
                        + "\"mood\":\"우울\",\"riskFlag\":false,\"alertLevel\":\"INFO\"}"));
        companionService.finish(WARD, second);
        awaitAsyncIdle();

        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_NOTICE)).as("하루 1번").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM companion_notice_log WHERE ward_id = ? AND days = 3", WARD)).isEqualTo(1);
        assertThat(companionStatsService.today().alertSentCount()).as("관리자 알림 보냄 수").isEqualTo(1);
    }

    @Test
    @DisplayName("PR-B 보호자 조회: 연결된 보호자는 오늘 기분을 보고, 연결되지 않은 보호자는 403")
    void 보호자_조회_권한() {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser("GCM002", Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        saveFinished(LocalDate.now(ZoneId.of("Asia/Seoul")), CompanionSlot.S09, "CALM");

        assertThat(companionGuardianService.today(GUARDIAN, WARD).current().mood()).isEqualTo("CALM");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> companionGuardianService.today("GCM002", WARD))
                .isInstanceOf(kr.silverbridge.main.global.exception.CustomException.class);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("테스트 계정(2026-11-10): 같은 시간대 테스트 대화 2번이 모두 저장·집계되고, 위험 알림은 대화당 1번, "
            + "analysis는 DB·로그에 없다. 테스트 안내는 디스패처 1건이고 선점 기록이 없다")
    void 테스트_대화는_일반_대화처럼_저장_집계된다(CapturedOutput output) throws Exception {
        String tester = "WCMT01";
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(tester, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, tester);
        signedIn(GUARDIAN);
        signedIn(tester);
        jdbc.update("INSERT INTO companion_question (slot, text) VALUES ('21:00', '저녁 시험 질문')");
        when(aiCompanionClient.openDialogue(eq(tester), anyLong(), eq("21:00"), eq("저녁 시험 질문"), anyLong()))
                .thenReturn(json("{\"dialogueId\":\"d-test-1\",\"maxTurns\":8}"),
                        json("{\"dialogueId\":\"d-test-2\",\"maxTurns\":8}"));
        // 첫 대화: 위험 턴 2번 + URGENT 마감 → 위험 알림 1번
        when(aiCompanionClient.replyText(eq("d-test-1"), anyString())).thenReturn(json(
                "{\"turnNo\":1,\"turnResult\":{\"label\":\"우울\",\"source\":\"TEXT\"},\"reply\":{\"text\":\"힘드셨군요.\"},"
                        + "\"face\":{\"label\":\"SecretFaceLabel\",\"usable\":true,\"totalFrames\":0,\"facesFound\":0},"
                        + "\"signals\":{\"emotion\":\"SecretSignalValue\",\"riskFlag\":true},\"riskFlag\":true,\"ended\":false}"));
        when(aiCompanionClient.finish("d-test-1")).thenReturn(json(
                "{\"dialogueId\":\"d-test-1\",\"endReason\":\"USER_STOP\",\"turnCount\":2,\"mood\":\"DEPRESSED\","
                        + "\"riskFlag\":true,\"alertLevel\":\"URGENT\"}"));
        when(aiCompanionClient.finish("d-test-2")).thenReturn(json(
                "{\"dialogueId\":\"d-test-2\",\"endReason\":\"USER_STOP\",\"turnCount\":0,\"mood\":\"JOY\","
                        + "\"riskFlag\":false,\"alertLevel\":\"INFO\"}"));

        var first = companionService.openTest(tester, "21:00");
        var turn = companionService.replyText(tester, first.sessionId(), "요즘 너무 힘들어요");
        assertThat(turn.analysis()).isNotNull();
        assertThat(turn.analysis().turnLabel()).isEqualTo("우울");
        assertThat(turn.analysis().riskFlag()).isTrue();
        companionService.replyText(tester, first.sessionId(), "그냥 다 힘들어요");
        var firstResult = companionService.finish(tester, first.sessionId());
        assertThat(firstResult.analysis().alertLevel()).isEqualTo("URGENT");
        assertThat(firstResult.analysis().test()).isTrue();
        eventually().untilAsserted(() -> assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK))
                .as("테스트 대화도 위험 알림 1번").isEqualTo(1));

        // 같은 시간대 다시 - 일반 대화였다면 COMPANION_SLOT_DONE
        var second = companionService.openTest(tester, "21:00");
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(second.resumed()).isFalse();
        companionService.finish(tester, second.sessionId());
        awaitAsyncIdle();

        assertThat(count("SELECT count(*) FROM companion_session WHERE ward_id = ? AND slot = '21:00' AND is_test = true",
                tester)).as("같은 시간대 테스트 대화 2행").isEqualTo(2);
        assertThat(notificationLogs(GUARDIAN, NotificationType.COMPANION_RISK)).as("대화당 1번").isEqualTo(1);

        // 집계: 보호자 화면·관리자 기분 분포 모두 마지막 테스트 대화(JOY)로 잡힌다
        var guardianToday = companionGuardianService.today(GUARDIAN, tester);
        assertThat(guardianToday.current().mood()).isEqualTo("JOY");
        assertThat(guardianToday.slots()).filteredOn(s -> s.slot().equals("21:00"))
                .singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("DONE"));
        var stats = companionStatsService.today();
        assertThat(stats.moodDistribution().joy()).isEqualTo(1);
        assertThat(stats.alertSentCount()).as("위험 알림이 관리자 알림 수에 잡힌다").isEqualTo(1);

        // 테스트 안내: 디스패처 1건(data에 test=true), companion_prompt_log 없음 → 응답률 집계(askedCount) 그대로
        companionService.sendTestPrompt(tester, "21:00");
        companionService.sendTestPrompt(tester, "21:00");
        verify(notificationDispatcher, org.mockito.Mockito.times(2)).dispatch(eq(tester), eq(tester),
                eq(NotificationType.COMPANION_PROMPT), org.mockito.ArgumentMatchers.argThat(c ->
                        "true".equals(c.data().get("test")) && "21:00".equals(c.data().get("slot"))));
        assertThat(count("SELECT count(*) FROM companion_prompt_log WHERE ward_id = ?", tester)).isZero();
        assertThat(companionStatsService.today().askedCount()).isZero();

        // analysis는 응답으로만 - 저장 칸도 로그도 없다
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'companion_session'"
                + " AND (column_name LIKE '%analysis%' OR column_name LIKE '%signal%' OR column_name LIKE '%face%')")).isZero();
        assertThat(output.getAll()).doesNotContain("SecretFaceLabel").doesNotContain("SecretSignalValue");
    }

    @Test
    @DisplayName("부분 UNIQUE(V68): 일반 대화는 시간대당 1행 그대로, 테스트 대화는 같은 시간대에 여러 행")
    void 일반_대화는_시간대당_한_번_그대로() {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES ('09:00', '시험 질문') RETURNING id", Long.class);
        String insert = "INSERT INTO companion_session (ward_id, session_date, slot, question_id, status, is_test, "
                + "started_at, last_activity_at) VALUES (?, CURRENT_DATE, '09:00', ?, 'OPEN', ?, now(), now())";
        jdbc.update(insert, WARD, questionId, true);
        jdbc.update(insert, WARD, questionId, true);
        jdbc.update(insert, WARD, questionId, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, WARD, questionId, false))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM companion_session WHERE ward_id = ?", WARD)).isEqualTo(3);
    }

    /** 지난날 마친 대화(연속 일수 준비). 연결 이후로 보이게 시작 시각을 지금으로 둔다. */
    private void saveFinished(LocalDate date, CompanionSlot slot, String mood) {
        saveFinished(date, slot, mood, java.time.OffsetDateTime.now());
    }

    /** 시작 시각을 정해 넣는다 - 연결 전에 시작한 대화(D-1)를 만들 때 쓴다. */
    private void saveFinished(LocalDate date, CompanionSlot slot, String mood, java.time.OffsetDateTime startedAt) {
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES (?, '시험 질문') RETURNING id", Long.class, slot.code());
        jdbc.update("INSERT INTO companion_session (ward_id, session_date, slot, question_id, dialogue_id, status, "
                        + "end_reason, mood, turn_count, answered_count, no_response_count, started_at, last_activity_at, "
                        + "ended_at) VALUES (?, ?, ?, ?, ?, 'FINISHED', 'MAX_TURNS', ?, 8, 8, 0, ?, ?, ?)",
                WARD, date, slot.code(), questionId, "d-past-" + date + slot.code(), mood, startedAt, startedAt, startedAt);
    }

    private long openSession(String dialogueId) {
        return openSession(dialogueId, CompanionSlot.S09);
    }

    /**
     * AI에 열린 대화 1개. 시간대 창과 무관하게 대화를 이어 갈 수 있다. 질문은 직접 넣는다 - 기반 클래스가 테스트마다
     * 전 표를 TRUNCATE해 V65 초기 질문도 지워진다.
     */
    private long openSession(String dialogueId, CompanionSlot slot) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES (?, '시험 질문') RETURNING id", Long.class, slot.code());
        CompanionSession session = CompanionSession.claim(WARD, LocalDate.now(ZoneId.of("Asia/Seoul")),
                slot, questionId, now);
        session.attachDialogue(dialogueId, 8, now);
        return sessionRepository.save(session).getId();
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}
