package kr.silverbridge.main.domain.companion;

import jakarta.persistence.EntityManager;
import kr.silverbridge.main.domain.companion.entity.CompanionQuestion;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 안부 말벗 V65 - 질문 초기 데이터, 시간대 UNIQUE, 위험 알림 선점, 멈춘 대화 조회, 탈퇴 CASCADE(실 DB). */
class CompanionSessionRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 4, 9, 10, 0, 0, ZoneOffset.ofHours(9));
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Autowired
    private CompanionSessionRepository sessionRepository;
    @Autowired
    private CompanionQuestionRepository questionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private Long questionId;

    @BeforeEach
    void setUp() {
        userRepository.saveAndFlush(TestData.user("CW0001", "어르신", Role.WARD));
        questionId = questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S09).get(0).getId();
    }

    @Test
    @DisplayName("V65 초기 질문은 시간대마다 3문장 이상이다")
    void seededQuestions() {
        for (CompanionSlot slot : CompanionSlot.values()) {
            List<CompanionQuestion> questions = questionRepository.findBySlotAndActiveTrueOrderByIdAsc(slot);
            assertThat(questions).as(slot.code()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(questions).allSatisfy(q -> assertThat(q.getText()).isNotBlank().hasSizeLessThanOrEqualTo(200));
        }
    }

    @Test
    @DisplayName("같은 피보호자·날짜·시간대에는 대화를 하나만 만든다")
    void oneSessionPerSlot() {
        sessionRepository.saveAndFlush(CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW));

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("위험 알림 선점은 대화당 한 번만 이긴다")
    void riskClaimOnce() {
        CompanionSession session = sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW));

        assertThat(sessionRepository.claimRiskNotification(session.getId(), NOW)).isEqualTo(1);
        assertThat(sessionRepository.claimRiskNotification(session.getId(), NOW.plusMinutes(1))).isZero();
    }

    @Test
    @DisplayName("멈춘 대화 조회는 기준 시각 이전에 활동이 멈춘 OPEN만 돌려준다")
    void findStale() {
        CompanionSession stale = sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW.minusMinutes(30)));
        CompanionSession fresh = sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S12,
                        questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S12).get(0).getId(), NOW));

        List<CompanionSession> found = sessionRepository.findStale(CompanionSessionStatus.OPEN, NOW.minusMinutes(10),
                PageRequest.of(0, 10));

        assertThat(found).extracting(CompanionSession::getId).containsExactly(stale.getId());
        assertThat(found).extracting(CompanionSession::getId).doesNotContain(fresh.getId());
    }

    @Test
    @DisplayName("이어 가기 활동 시각 갱신(최종 점검 L-3): 진행 중(OPEN)이면 1행 - 시각만 바뀌고 상태·버전은 그대로다")
    void touchActivityUpdatesOpenSession() {
        CompanionSession session = sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW.minusMinutes(9)));
        session.attachDialogue("d-touch", 8, NOW.minusMinutes(9));
        sessionRepository.saveAndFlush(session);
        long versionBefore = session.getVersion();

        int updated = sessionRepository.touchActivity(session.getId(), CompanionSessionStatus.OPEN, NOW);

        assertThat(updated).isEqualTo(1);
        entityManager.clear();
        CompanionSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getLastActivityAt().toInstant()).isEqualTo(NOW.toInstant());
        assertThat(reloaded.getStatus()).isEqualTo(CompanionSessionStatus.OPEN);
        assertThat(reloaded.getVersion()).as("버전을 올리지 않는다 - 같은 순간의 턴 기록·마감이 실패하지 않게").isEqualTo(versionBefore);
        // 밀린 대화는 마감 대상 목록에서 빠진다
        assertThat(sessionRepository.findStale(CompanionSessionStatus.OPEN, NOW.minusMinutes(5),
                PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    @DisplayName("이어 가기 활동 시각 갱신(최종 점검 L-3): 끝난 대화(FINISHED)·끊긴 대화(LOST)·없는 번호는 0행 - 시각을 건드리지 않는다")
    void touchActivityIgnoresClosedOrMissing() {
        OffsetDateTime endedAt = NOW.minusMinutes(20);
        CompanionSession finished = CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW.minusMinutes(30));
        finished.attachDialogue("d-fin", 8, NOW.minusMinutes(30));
        finished.finish(kr.silverbridge.main.domain.companion.entity.CompanionEndReason.USER_STOP, null, 2, 2, 0,
                false, false, null, endedAt);
        finished = sessionRepository.saveAndFlush(finished);
        CompanionSession lost = CompanionSession.claim("CW0001", TODAY, CompanionSlot.S12,
                questionRepository.findBySlotAndActiveTrueOrderByIdAsc(CompanionSlot.S12).get(0).getId(), NOW.minusMinutes(30));
        lost.attachDialogue("d-lost", 8, NOW.minusMinutes(30));
        lost.markLost(endedAt);
        lost = sessionRepository.saveAndFlush(lost);

        assertThat(sessionRepository.touchActivity(finished.getId(), CompanionSessionStatus.OPEN, NOW)).isZero();
        assertThat(sessionRepository.touchActivity(lost.getId(), CompanionSessionStatus.OPEN, NOW)).isZero();
        assertThat(sessionRepository.touchActivity(Long.MAX_VALUE, CompanionSessionStatus.OPEN, NOW)).isZero();

        entityManager.clear();
        assertThat(sessionRepository.findById(finished.getId()).orElseThrow().getLastActivityAt().toInstant())
                .isEqualTo(endedAt.toInstant());
        assertThat(sessionRepository.findById(lost.getId()).orElseThrow().getLastActivityAt().toInstant())
                .isEqualTo(endedAt.toInstant());
    }

    @Test
    @DisplayName("회원 행을 지우면(탈퇴 hard delete) 대화 결과도 함께 지워진다")
    void cascadeOnUserDelete() {
        CompanionSession session = sessionRepository.saveAndFlush(
                CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, questionId, NOW));
        entityManager.clear();

        userRepository.deleteById("CW0001");
        userRepository.flush();
        entityManager.clear();

        assertThat(sessionRepository.findById(session.getId())).isEmpty();
    }
}
