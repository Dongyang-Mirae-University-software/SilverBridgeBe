package kr.silverbridge.main.domain.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminUserUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.companion.dto.CompanionGuardianDtos.SessionDetailResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import kr.silverbridge.main.domain.companion.service.CompanionGuardianService;
import kr.silverbridge.main.domain.companion.service.CompanionService;
import kr.silverbridge.main.domain.companion.service.CompanionTranscriptService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 대화 내용 저장(동의·30일) - 서비스 → 실 DB(V71) → 보호자 조회까지 (2026-11-11).
 * 동의 없음 → 기분만 / 동의 → 글 저장 / 철회 → 즉시 비움 / 30일 정리 / 역할 변경·탈퇴 정리 / 철회와 턴 저장 경합.
 */
class CompanionTranscriptFullIntegrationTest extends FullContextIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ADMIN = "ACT001";
    private static final String GUARDIAN = "GCT001";
    private static final String WARD = "WCT001";

    @Autowired private CompanionService companionService;
    @Autowired private CompanionGuardianService guardianService;
    @Autowired private CompanionTranscriptService transcriptService;
    @Autowired private CompanionSessionRepository sessionRepository;
    @Autowired private CompanionTurnRepository turnRepository;
    @Autowired private CompanionTranscriptConsentRepository consentRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("동의 없음 → 기분만 저장, 동의 → 글 저장·보호자에게 보임, 철회 → 즉시 비우고 NO_CONSENT. 로그에 글 없음")
    void 동의에_따라_글을_저장하고_철회하면_지운다(CapturedOutput output) throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        long sessionId = openSession("d-tr-1");
        when(aiCompanionClient.replyText(eq("d-tr-1"), anyString()))
                .thenReturn(JSON.readTree("{\"turnNo\":1,\"reply\":{\"text\":\"비밀문장하나\"},"
                        + "\"turnResult\":{\"label\":\"걱정\"},\"ended\":false}"))
                .thenReturn(JSON.readTree("{\"turnNo\":2,\"reply\":{\"text\":\"비밀문장둘\"},"
                        + "\"turnResult\":{\"label\":\"평온\"},\"ended\":false}"));

        companionService.replyText(WARD, sessionId, "동의전대답");
        transcriptService.setConsent(WARD, true);
        companionService.replyText(WARD, sessionId, "동의후대답");

        assertThat(turnRepository.findBySessionIdOrderByTurnNoAsc(sessionId))
                .extracting(CompanionTurn::getTurnNo, t -> t.getMood().name(), CompanionTurn::getBotText,
                        CompanionTurn::getAnswerText)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "WORRY", null, null),
                        org.assertj.core.groups.Tuple.tuple(2, "CALM", "비밀문장둘", "동의후대답"));
        SessionDetailResponse detail = guardianService.session(GUARDIAN, WARD, sessionId);
        assertThat(detail.transcript()).isEqualTo("AVAILABLE");
        assertThat(detail.turns().get(1).answerText()).isEqualTo("동의후대답");

        transcriptService.setConsent(WARD, false);

        assertThat(count("SELECT count(*) FROM companion_turn WHERE bot_text IS NOT NULL OR answer_text IS NOT NULL"))
                .as("철회 즉시 글 비움").isZero();
        assertThat(count("SELECT count(*) FROM companion_turn WHERE mood IS NOT NULL")).as("기분은 남김").isEqualTo(2);
        assertThat(guardianService.session(GUARDIAN, WARD, sessionId).transcript()).isEqualTo("NO_CONSENT");
        assertThat(output.getAll()).doesNotContain("비밀문장", "동의전대답", "동의후대답");
    }

    @Test
    @DisplayName("30일 정리: 30일 지난 턴의 글만 비우고 기분·최근 글은 남긴다")
    void 삼십일_지난_글을_비운다() {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        long sessionId = openSession("d-tr-ret");
        OffsetDateTime now = OffsetDateTime.now(KST);
        insertTurn(sessionId, 1, "오래된말", now.minusDays(31));
        insertTurn(sessionId, 2, "최근말", now.minusDays(29));

        int cleared = transcriptService.clearExpired();

        assertThat(cleared).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT answer_text FROM companion_turn WHERE session_id = ? ORDER BY turn_no",
                String.class, sessionId)).containsExactly(null, "최근말");
        assertThat(count("SELECT count(*) FROM companion_turn WHERE mood = 'CALM'")).isEqualTo(2);
    }

    @Test
    @DisplayName("역할 변경(피보호자 → 보호자): 동의 행과 턴(글 포함)을 지운다. 탈퇴(회원 행 삭제): FK CASCADE로 둘 다 지워진다")
    void 역할_변경과_탈퇴는_대화_내용을_지운다() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        transcriptService.setConsent(WARD, true);
        insertTurn(openSession("d-tr-role"), 1, "역할변경전", OffsetDateTime.now(KST));

        adminUserService.updateUser(WARD, new AdminUserUpdateRequest(null, Role.GUARDIAN, null, null), ADMIN);

        assertThat(count("SELECT count(*) FROM companion_transcript_consent WHERE ward_id = ?", WARD)).isZero();
        assertThat(count("SELECT count(*) FROM companion_turn")).isZero();

        String other = "WCT002";
        saveUser(other, Role.WARD, Status.ACTIVE);
        jdbc.update("INSERT INTO companion_transcript_consent (ward_id, consented_at) VALUES (?, now())", other);
        insertTurn(openSession(other, "d-tr-wd"), 1, "탈퇴전", OffsetDateTime.now(KST));

        jdbc.update("DELETE FROM users WHERE id = ?", other);

        assertThat(count("SELECT count(*) FROM companion_transcript_consent")).isZero();
        assertThat(count("SELECT count(*) FROM companion_turn")).isZero();
    }

    @Test
    @DisplayName("경합: 턴 저장이 동의 행을 FOR SHARE로 잡은 사이 철회가 들어오면, 철회는 그 커밋을 기다렸다가 새 글까지 비운다")
    void 철회와_턴_저장이_겹쳐도_글이_남지_않는다() throws Exception {
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        transcriptService.setConsent(WARD, true);
        long sessionId = openSession("d-tr-race");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);

        CompletableFuture<Void> turnTx = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            assertThat(consentRepository.lockIfConsented(WARD)).isNotEmpty();
            locked.countDown();
            sleep(800);
            turnRepository.save(CompanionTurn.of(sessionId, 1, null, "경합말벗", "경합대답", OffsetDateTime.now(KST)));
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        transcriptService.setConsent(WARD, false);
        turnTx.get(10, TimeUnit.SECONDS);

        assertThat(count("SELECT count(*) FROM companion_turn WHERE bot_text IS NOT NULL OR answer_text IS NOT NULL"))
                .isZero();
        assertThat(count("SELECT count(*) FROM companion_turn")).isEqualTo(1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void insertTurn(long sessionId, int turnNo, String answer, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO companion_turn (session_id, turn_no, mood, bot_text, answer_text, created_at) "
                + "VALUES (?, ?, 'CALM', '말벗', ?, ?)", sessionId, turnNo, answer, createdAt);
    }

    private long openSession(String dialogueId) {
        return openSession(WARD, dialogueId);
    }

    private long openSession(String wardId, String dialogueId) {
        OffsetDateTime now = OffsetDateTime.now(KST);
        Long questionId = jdbc.queryForObject(
                "INSERT INTO companion_question (slot, text) VALUES ('09:00', '시험 질문') RETURNING id", Long.class);
        CompanionSession session = CompanionSession.claim(wardId, LocalDate.now(KST), CompanionSlot.S09, questionId, now);
        session.attachDialogue(dialogueId, 8, now);
        return sessionRepository.save(session).getId();
    }
}
