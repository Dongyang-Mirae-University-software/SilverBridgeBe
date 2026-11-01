package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.entity.CheckinSession;
import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSettingRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.channel.WardNotificationText;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckinPromptPlannerTest {

    private static final String WARD = "wA1b2C";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final CheckinProperties properties = new CheckinProperties();
    private final CheckinClock clock = mock(CheckinClock.class);
    private final CheckinSettingRepository settingRepository = mock(CheckinSettingRepository.class);
    private final CheckinResultRepository resultRepository = mock(CheckinResultRepository.class);
    private final CheckinSessionRepository sessionRepository = mock(CheckinSessionRepository.class);
    private final CheckinNotifyLogRepository logRepository = mock(CheckinNotifyLogRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final CheckinGuardianAlertService alertService = mock(CheckinGuardianAlertService.class);
    private CheckinPromptPlanner planner;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("k");
        properties.setPromptSpreadMinutes(1);   // 분산 0분 - 시각 정각부터 대상
        planner = new CheckinPromptPlanner(properties, clock, settingRepository, resultRepository, sessionRepository,
                logRepository, connectionService, userRepository, dispatcher, alertService);
        CheckinSetting setting = CheckinSetting.of(WARD, false);
        setting.changePromptEnabled(true, "gA1b2C");
        when(settingRepository.findByPromptEnabledTrue()).thenReturn(List.of(setting));
        User ward = mock(User.class);
        when(ward.getStatus()).thenReturn(Status.ACTIVE);
        when(ward.getRole()).thenReturn(Role.WARD);
        when(userRepository.findById(WARD)).thenReturn(Optional.of(ward));
        when(connectionService.getActiveGuardianIds(WARD)).thenReturn(List.of("gA1b2C"));
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(TODAY.atTime(hour, minute), KST);
    }

    @Test
    @DisplayName("꺼진 서버면 아무것도 하지 않는다")
    void disabled() {
        properties.setEnabled(false);
        planner.run();
        verifyNoInteractions(settingRepository, dispatcher, clock);
    }

    @Test
    @DisplayName("응답 창 안이면 선점한 뒤에 피보호자에게 FCM 안부 알림(선점 후 발송 순서)")
    void claimThenSend() {
        when(logRepository.claimPrompt(WARD, TODAY, "12:00")).thenReturn(1);

        planner.runOnce(at(12, 3));

        InOrder order = inOrder(logRepository, dispatcher);
        order.verify(logRepository).claimPrompt(WARD, TODAY, "12:00");
        order.verify(dispatcher).dispatch(eq(WARD), eq(WARD), eq(NotificationType.CHECKIN_PROMPT), any());
    }

    @Test
    @DisplayName("이미 선점됐으면(다른 주기·인스턴스) 보내지 않는다")
    void alreadyClaimed() {
        when(logRepository.claimPrompt(WARD, TODAY, "12:00")).thenReturn(0);
        planner.runOnce(at(12, 3));
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("이미 결과가 있거나, ACTIVE 보호자가 없거나, 정지 계정이면 선점도 하지 않는다")
    void notEligible() {
        when(resultRepository.existsByWardIdAndCheckDateAndSlot(WARD, TODAY, CheckinSlot.AT_12)).thenReturn(true);
        planner.runOnce(at(12, 3));
        when(resultRepository.existsByWardIdAndCheckDateAndSlot(WARD, TODAY, CheckinSlot.AT_12)).thenReturn(false);
        when(connectionService.getActiveGuardianIds(WARD)).thenReturn(List.of());
        planner.runOnce(at(12, 3));
        verify(logRepository, never()).claimPrompt(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("응답 창(1시간)이 지나면 안부 알림을 보내지 않는다")
    void outsideWindow() {
        planner.runOnce(at(13, 0));
        verify(logRepository, never()).claimPrompt(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("분산: 피보호자별 고정 오프셋 전에는 보내지 않는다(같은 값은 재시작해도 같다)")
    void spread() {
        properties.setPromptSpreadMinutes(5);
        int offset = planner.spreadOffset(WARD, TODAY, CheckinSlot.AT_09);
        assertThat(offset).isBetween(0, 4).isEqualTo(planner.spreadOffset(WARD, TODAY, CheckinSlot.AT_09));
        if (offset > 0) {
            planner.runOnce(at(9, offset - 1));
            verify(logRepository, never()).claimPrompt(anyString(), any(), anyString());
        }
        when(logRepository.claimPrompt(WARD, TODAY, "09:00")).thenReturn(1);
        planner.runOnce(at(9, offset));
        verify(logRepository).claimPrompt(WARD, TODAY, "09:00");
    }

    @Test
    @DisplayName("응답 창이 닫히고 결과도, 나눈 말도 없으면 보호자 미응답 알림 / 창 닫힘 + 1시간 뒤에는 보지 않는다")
    void missed() {
        when(logRepository.promptedWards(TODAY, "09:00")).thenReturn(List.of(WARD));

        planner.runOnce(at(10, 5));
        verify(alertService).sendMissed(WARD, TODAY, CheckinSlot.AT_09);

        planner.runOnce(at(11, 1));
        verify(logRepository, never()).promptedWards(TODAY, "12:00");
        verify(alertService).sendMissed(WARD, TODAY, CheckinSlot.AT_09);   // 여전히 한 번
    }

    @Test
    @DisplayName("말을 나눈 대화가 있으면(정리 전이라도) 미응답이 아니다")
    void talkedIsNotMissed() {
        when(logRepository.promptedWards(TODAY, "09:00")).thenReturn(List.of(WARD));
        CheckinSession s = CheckinSession.start("ck_abcdefghijklmnopqrstuvwx", WARD, TODAY, CheckinSlot.AT_09, "q",
                at(9, 10));
        s.recordTurn(false, false, false);
        when(sessionRepository.findByWardIdAndCheckDate(WARD, TODAY)).thenReturn(List.of(s));

        planner.runOnce(at(10, 5));

        verify(alertService, never()).sendMissed(anyString(), any(), any());
    }

    @Test
    @DisplayName("피보호자 안부 알림 문구는 단문 문자 한도(90바이트) 안이다")
    void promptFitsSingleSms() {
        assertThat(WardNotificationText.fitsSingleSms(CheckinPromptPlanner.PROMPT_BODY)).isTrue();
        assertThat(NotificationContent.of(WardNotificationText.TITLE, CheckinPromptPlanner.PROMPT_BODY, java.util.Map.of())
                .title()).isEqualTo(WardNotificationText.TITLE);
    }
}
