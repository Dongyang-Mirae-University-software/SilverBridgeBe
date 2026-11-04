package kr.silverbridge.main.domain.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired private CompanionQuestionRepository questionRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private UserService userService;

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

    /** AI에 열린 대화 1개(질문은 V65 초기 데이터). 시간대 창과 무관하게 대화를 이어 갈 수 있다. */
    private long openSession(String dialogueId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
        Long questionId = questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S09).get(0).getId();
        CompanionSession session = CompanionSession.claim(WARD, LocalDate.now(ZoneId.of("Asia/Seoul")),
                CompanionSlot.S09, questionId, now);
        session.attachDialogue(dialogueId, 8, now);
        return sessionRepository.save(session).getId();
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }
}
