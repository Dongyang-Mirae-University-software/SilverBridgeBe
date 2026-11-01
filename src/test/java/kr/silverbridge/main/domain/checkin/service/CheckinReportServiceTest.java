package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse;
import kr.silverbridge.main.domain.checkin.dto.CheckinDailyResponse.SlotStatus;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinEndReason;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import kr.silverbridge.main.domain.checkin.repository.CheckinNotifyLogRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinResultRepository;
import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CheckinReportServiceTest {

    private static final String GUARDIAN = "gA1b2C";
    private static final String WARD = "wA1b2C";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final CheckinProperties properties = new CheckinProperties();
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final CheckinClock clock = mock(CheckinClock.class);
    private final CheckinResultRepository resultRepository = mock(CheckinResultRepository.class);
    private final CheckinSessionRepository sessionRepository = mock(CheckinSessionRepository.class);
    private final CheckinNotifyLogRepository logRepository = mock(CheckinNotifyLogRepository.class);
    private CheckinReportService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAiBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("k");
        service = new CheckinReportService(properties, new CheckinGuardianAccess(connectionService), clock,
                resultRepository, sessionRepository, logRepository);
        when(clock.now()).thenReturn(OffsetDateTime.of(TODAY.atTime(15, 30), KST));
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(connectionService.isVisibleSinceConnection(eq(GUARDIAN), eq(WARD), any())).thenReturn(true);
    }

    @Test
    @DisplayName("연결되지 않은 피보호자는 403이고 기록을 읽지 않는다")
    void notConnected() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);
        assertThatThrownBy(() -> service.daily(GUARDIAN, WARD, null))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_NOT_AUTHORIZED);
        verifyNoInteractions(resultRepository);
    }

    @Test
    @DisplayName("미래 날짜·60일보다 이전은 400")
    void invalidDate() {
        assertThatThrownBy(() -> service.daily(GUARDIAN, WARD, TODAY.plusDays(1)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_INVALID_DATE);
        assertThatThrownBy(() -> service.weekly(GUARDIAN, WARD, TODAY.minusDays(61)))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CHECKIN_INVALID_DATE);
    }

    @Test
    @DisplayName("시각별 상태: 마침 / 알림 받고 미응답 / 알림 없음 / 열림 / 아직")
    void statuses() {
        when(resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(WARD, TODAY, TODAY))
                .thenReturn(List.of(result(CheckinSlot.AT_09, true)));
        when(logRepository.promptedSlots(WARD, TODAY)).thenReturn(List.of("09:00", "12:00", "15:00"));

        CheckinDailyResponse r = service.daily(GUARDIAN, WARD, null);

        assertThat(r.slots()).extracting(CheckinDailyResponse.SlotItem::status).containsExactly(
                SlotStatus.DONE, SlotStatus.MISSED, SlotStatus.OPEN, SlotStatus.UPCOMING, SlotStatus.UPCOMING);
        assertThat(r.attention()).isTrue();
        CheckinDailyResponse.SlotItem done = r.slots().get(0);
        assertThat(done.summary()).isEqualTo("잠을 설치셨다고 하셨어요.");
        assertThat(done.signals().sleep()).isEqualTo("BAD");
        assertThat(done.attentionReasons()).containsExactly("SLEEP_UNUSUAL", "MEAL_UNUSUAL");
        // 판정이 시각에 단조라 처음 보인 뒤로는 연결을 다시 조회하지 않는다(점검 L-2)
        org.mockito.Mockito.verify(connectionService, org.mockito.Mockito.times(1))
                .isVisibleSinceConnection(eq(GUARDIAN), eq(WARD), any());
    }

    @Test
    @DisplayName("지금 연결 이전 기록은 내용 없이 BEFORE_CONNECTION (D-1)")
    void beforeConnection() {
        when(resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(WARD, TODAY, TODAY))
                .thenReturn(List.of(result(CheckinSlot.AT_09, true)));
        when(connectionService.isVisibleSinceConnection(eq(GUARDIAN), eq(WARD), any())).thenReturn(false);

        CheckinDailyResponse r = service.daily(GUARDIAN, WARD, null);

        assertThat(r.slots()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo(SlotStatus.BEFORE_CONNECTION);
            assertThat(s.summary()).isNull();
            assertThat(s.emotion()).isNull();
        });
        assertThat(r.attention()).isFalse();
    }

    @Test
    @DisplayName("7일 추세: 마친 수·확인 필요 수·창이 닫힌 미응답 수")
    void weekly() {
        when(resultRepository.findByWardIdAndCheckDateBetweenOrderByCheckDateAscSlotAsc(WARD, TODAY.minusDays(6), TODAY))
                .thenReturn(List.of(result(CheckinSlot.AT_09, true)));
        when(logRepository.promptedSlots(WARD, TODAY)).thenReturn(List.of("09:00", "12:00", "15:00"));

        var w = service.weekly(GUARDIAN, WARD, null);

        assertThat(w.days()).hasSize(7);
        var today = w.days().get(6);
        assertThat(today.date()).isEqualTo(TODAY);
        assertThat(today.done()).isEqualTo(1);
        assertThat(today.attention()).isEqualTo(1);
        assertThat(today.missed()).isEqualTo(1);   // 12:00만 (15:00은 아직 창이 열려 있다)
        assertThat(today.emotions()).hasSize(1);
    }

    private static CheckinResult result(CheckinSlot slot, boolean attention) {
        return CheckinResult.builder().wardId(WARD).checkDate(TODAY).slot(slot).emotion(CheckinEmotion.WORRIED)
                .sleep("BAD").meal("SKIPPED").summary("잠을 설치셨다고 하셨어요.").evidence(List.of())
                .endReason(CheckinEndReason.USER_END).createdAt(OffsetDateTime.of(TODAY.atTime(9, 5), KST))
                .attention(attention).attentionReasons(List.of("SLEEP_UNUSUAL", "MEAL_UNUSUAL")).build();
    }
}
