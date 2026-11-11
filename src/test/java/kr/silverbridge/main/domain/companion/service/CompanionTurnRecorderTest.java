package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.entity.CompanionTurn;
import kr.silverbridge.main.domain.companion.repository.CompanionTranscriptConsentRepository;
import kr.silverbridge.main.domain.companion.repository.CompanionTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 턴 1행 저장 - 기분은 항상, 글은 동의했을 때만(2026-11-11, 개인정보 보호법 제23조). */
class CompanionTurnRecorderTest {

    private static final String W = "CW0001";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 11, 9, 5, 0, 0, ZoneOffset.ofHours(9));

    private CompanionTurnRepository turnRepository;
    private CompanionTranscriptConsentRepository consentRepository;
    private CompanionTurnRecorder recorder;
    private CompanionSession session;

    @BeforeEach
    void setUp() {
        turnRepository = mock(CompanionTurnRepository.class);
        consentRepository = mock(CompanionTranscriptConsentRepository.class);
        recorder = new CompanionTurnRecorder(turnRepository, consentRepository);
        session = CompanionTestSessions.open(W, DAY, CompanionSlot.S09);
    }

    private CompanionTurn saved() {
        ArgumentCaptor<CompanionTurn> captor = ArgumentCaptor.forClass(CompanionTurn.class);
        verify(turnRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("동의 없음: 기분만 저장하고 말벗 문장·대답 글은 저장하지 않는다")
    void noConsentStoresMoodOnly() {
        when(consentRepository.lockIfConsented(W)).thenReturn(List.of());

        recorder.record(session, 1, new CompanionTurnRecorder.TurnContent(CompanionMood.WORRY, "그러셨군요.", "허리가 아파요"), NOW);

        CompanionTurn turn = saved();
        assertThat(turn.getMood()).isEqualTo(CompanionMood.WORRY);
        assertThat(turn.getBotText()).isNull();
        assertThat(turn.getAnswerText()).isNull();
        assertThat(turn.getSessionId()).isEqualTo(session.getId());
    }

    @Test
    @DisplayName("동의함: 글 두 칸을 화면 표시용 문자열 그대로 저장한다(정규화 없음)")
    void consentStoresText() {
        when(consentRepository.lockIfConsented(W)).thenReturn(List.of(1));

        recorder.record(session, 2, new CompanionTurnRecorder.TurnContent(null, "  오늘 날씨가 좋네요. ", "산책 다녀왔어요\n"), NOW);

        CompanionTurn turn = saved();
        assertThat(turn.getMood()).as("판단 불가 = null 그대로").isNull();
        assertThat(turn.getBotText()).isEqualTo("  오늘 날씨가 좋네요. ");
        assertThat(turn.getAnswerText()).isEqualTo("산책 다녀왔어요\n");
        assertThat(turn.getTurnNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("동의했어도 빈 글은 null로 둔다(받아 적지 못한 음성 턴)")
    void blankTextIsNull() {
        when(consentRepository.lockIfConsented(W)).thenReturn(List.of(1));

        recorder.record(session, 3, new CompanionTurnRecorder.TurnContent(CompanionMood.CALM, "네.", "   "), NOW);

        assertThat(saved().getAnswerText()).isNull();
    }

    @Test
    @DisplayName("같은 턴 번호가 다시 오거나 번호가 0이면 저장하지 않는다(UNIQUE 위반으로 턴 기록이 롤백되지 않게)")
    void duplicateOrZeroTurnSkipped() {
        when(turnRepository.existsBySessionIdAndTurnNo(session.getId(), 1)).thenReturn(true);

        recorder.record(session, 1, CompanionTurnRecorder.TurnContent.NONE, NOW);
        recorder.record(session, 0, CompanionTurnRecorder.TurnContent.NONE, NOW);

        verify(turnRepository, never()).save(any());
        verify(consentRepository, never()).lockIfConsented(any());
    }

    @ParameterizedTest(name = "moodCode={0}, label={1} → {2}")
    @CsvSource(nullValues = "null", value = {
            "WORRY, null, WORRY",
            "null, 우울, DEPRESSED",
            "null, 판단 보류, null",
            "UNKNOWN, 기쁨, JOY",
            "null, null, null"})
    @DisplayName("턴 기분: moodCode 우선, 없거나 판단 불가면 한글 label, 모르는 값은 null")
    void turnMood(String moodCode, String label, String expected) throws Exception {
        String turnResult = "{" + (moodCode == null ? "" : "\"moodCode\":\"" + moodCode + "\"")
                + (moodCode != null && label != null ? "," : "")
                + (label == null ? "" : "\"label\":\"" + label + "\"") + "}";
        var data = new ObjectMapper().readTree("{\"turnResult\":" + turnResult + "}");

        CompanionMood mood = CompanionService.turnMood(data);

        assertThat(mood == null ? null : mood.name()).isEqualTo(expected);
    }
}
