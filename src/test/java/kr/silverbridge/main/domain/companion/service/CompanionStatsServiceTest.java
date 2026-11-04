package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.client.AiCompanionClient;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.dto.CompanionAdminStatsResponse;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionPromptLog;
import kr.silverbridge.main.domain.companion.entity.CompanionSessionStatus;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.repository.CompanionNoticeLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionPromptLogRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionQuestionRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static kr.silverbridge.main.domain.companion.service.CompanionTestSessions.at;
import static kr.silverbridge.main.domain.companion.service.CompanionTestSessions.finished;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 관리자 오늘 집계 - 응답률·대화 없는 어르신·기분 분포·이어지는 어르신·알림 수. 모르는 값은 null. */
class CompanionStatsServiceTest {

    private static final ZonedDateTime AT_2200 = ZonedDateTime.of(2026, 11, 4, 22, 0, 0, 0, CompanionService.KST);
    private static final LocalDate TODAY = AT_2200.toLocalDate();

    private CompanionSessionRepository sessionRepository;
    private CompanionPromptLogRepository promptLogRepository;
    private CompanionNoticeLogRepository noticeLogRepository;
    private UserRepository userRepository;
    private CompanionStatsService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CompanionSessionRepository.class);
        promptLogRepository = mock(CompanionPromptLogRepository.class);
        noticeLogRepository = mock(CompanionNoticeLogRepository.class);
        userRepository = mock(UserRepository.class);
        CompanionProperties properties = new CompanionProperties();
        CompanionService companionService = new CompanionService(mock(AiCompanionClient.class), properties,
                mock(CompanionQuestionRepository.class), sessionRepository, mock(CompanionSessionWriter.class),
                new CompanionTurnSlots(properties), mock(RateLimitService.class));
        companionService.setClock(Clock.fixed(AT_2200.toInstant(), CompanionService.KST));
        service = new CompanionStatsService(companionService, sessionRepository, promptLogRepository,
                noticeLogRepository, userRepository);
    }

    @Test
    @DisplayName("보낸 질문 안내가 없으면 응답률은 0이 아니라 null")
    void noPromptsMeansNullRate() {
        when(promptLogRepository.findByPromptDate(TODAY)).thenReturn(List.of());
        when(sessionRepository.findBySessionDate(TODAY)).thenReturn(List.of());

        CompanionAdminStatsResponse stats = service.today();

        assertThat(stats.askedCount()).isZero();
        assertThat(stats.responseRate()).isNull();
        assertThat(stats.moodDistribution().wardCount()).isZero();
    }

    @Test
    @DisplayName("응답률·대화 없는 어르신·기분 분포(판단 불가 따로)·이어지는 어르신(같은 일수 두 명도 각각)·알림 수")
    void aggregates() {
        when(promptLogRepository.findByPromptDate(TODAY)).thenReturn(List.of(
                CompanionPromptLog.of("WA0001", TODAY, CompanionSlot.S09, at(TODAY, CompanionSlot.S09, 0)),
                CompanionPromptLog.of("WA0001", TODAY, CompanionSlot.S12, at(TODAY, CompanionSlot.S12, 0)),
                CompanionPromptLog.of("WB0001", TODAY, CompanionSlot.S09, at(TODAY, CompanionSlot.S09, 0)),
                CompanionPromptLog.of("WC0001", TODAY, CompanionSlot.S09, at(TODAY, CompanionSlot.S09, 0))));
        when(sessionRepository.findBySessionDate(TODAY)).thenReturn(List.of(
                finished("WA0001", TODAY, CompanionSlot.S09, CompanionMood.JOY, 4),
                finished("WA0001", TODAY, CompanionSlot.S12, CompanionMood.WORRY, 4),
                finished("WB0001", TODAY, CompanionSlot.S09, null, 2)));
        when(sessionRepository.findAllFinishedSince(eq(CompanionSessionStatus.FINISHED), any())).thenReturn(List.of(
                finished("WD0001", TODAY, CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished("WD0001", TODAY.minusDays(1), CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished("WD0001", TODAY.minusDays(2), CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished("WE0001", TODAY, CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished("WE0001", TODAY.minusDays(1), CompanionSlot.S21, CompanionMood.WORRY, 3),
                finished("WE0001", TODAY.minusDays(2), CompanionSlot.S21, CompanionMood.WORRY, 3)));
        when(sessionRepository.findLastAnsweredAt(anyCollection(), eq(CompanionSessionStatus.FINISHED)))
                .thenReturn(List.of());
        when(noticeLogRepository.countByNoticeDate(TODAY)).thenReturn(2L);
        when(sessionRepository.countRiskNotifiedBetween(any(), any())).thenReturn(1L);
        List<User> users = List.of(user("WC0001", "다어르신"), user("WD0001", "라어르신"), user("WE0001", "마어르신"));
        when(userRepository.findAllById(any())).thenReturn(users);

        CompanionAdminStatsResponse stats = service.today();

        assertThat(stats.askedCount()).isEqualTo(4);
        assertThat(stats.responseRate()).isEqualTo(75); // 4건 중 3건(WA 2 + WB 1)
        assertThat(stats.noDialogueWardCount()).isEqualTo(1);
        assertThat(stats.noDialogueWards()).extracting("wardId", "name", "lastDialogueAt")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WC0001", "다어르신", null));
        assertThat(stats.moodDistribution()).extracting("wardCount", "joy", "worry", "unknown")
                .containsExactly(2, 0, 1, 1);
        assertThat(stats.continuingWards()).extracting("wardId", "days")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WD0001", 3),
                        org.assertj.core.groups.Tuple.tuple("WE0001", 3));
        assertThat(stats.alertSentCount()).isEqualTo(3);
        assertThat(stats.generatedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }

    private static User user(String id, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(name);
        return user;
    }
}
