package kr.silverbridge.main.domain.companion.service;

import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 여러 날 위험 누적 판정(2026-11-11) - 서로 다른 날 2일 이상, 7일 기간, 0.97 기준, 위험 알림 날만으로는 안 보냄. */
class CompanionRiskPatternTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final String W = "CW0001";
    private final CompanionProperties.RiskPattern config = new CompanionProperties.RiskPattern();

    @Test
    @DisplayName("같은 날 두 번 높아도 1일이라 알리지 않는다")
    void sameDayTwiceIsOneDay() {
        List<CompanionSession> sessions = List.of(
                turn(TODAY, CompanionSlot.S09, "0.98"),
                turn(TODAY, CompanionSlot.S18, "0.99"));

        assertThat(CompanionRiskPattern.qualifyingDays(sessions, TODAY, config)).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 두 날 높으면 2일로 알린다(기준값 0.97과 같아도 포함)")
    void twoDistinctDaysQualify() {
        List<CompanionSession> sessions = List.of(
                turn(TODAY.minusDays(6), CompanionSlot.S09, "0.9700"),
                turn(TODAY, CompanionSlot.S09, "0.98"));

        assertThat(CompanionRiskPattern.qualifyingDays(sessions, TODAY, config)).hasValue(2);
    }

    @Test
    @DisplayName("7일 기간 밖(8일 전 = 오늘 -7일)은 세지 않는다")
    void eightDaysAgoExcluded() {
        List<CompanionSession> sessions = List.of(
                turn(TODAY.minusDays(7), CompanionSlot.S09, "0.99"),
                turn(TODAY, CompanionSlot.S09, "0.99"));

        assertThat(CompanionRiskPattern.qualifyingDays(sessions, TODAY, config)).isEmpty();
    }

    @Test
    @DisplayName("0.97 미만과 확률 없음(null)은 높은 날이 아니다")
    void belowThresholdAndNullIgnored() {
        List<CompanionSession> sessions = List.of(
                turn(TODAY.minusDays(2), CompanionSlot.S09, "0.9699"),
                turn(TODAY.minusDays(1), CompanionSlot.S09, null),
                turn(TODAY, CompanionSlot.S09, "0.99"));

        assertThat(CompanionRiskPattern.qualifyingDays(sessions, TODAY, config)).isEmpty();
    }

    @Test
    @DisplayName("높은 날이 모두 이미 위험 알림을 받은 날이면 보내지 않는다")
    void onlyAlreadyAlertedDaysDoNotQualify() {
        List<CompanionSession> sessions = List.of(
                alerted(turn(TODAY.minusDays(3), CompanionSlot.S09, "0.995")),
                alerted(turn(TODAY, CompanionSlot.S09, "0.996")));

        assertThat(CompanionRiskPattern.qualifyingDays(sessions, TODAY, config)).isEmpty();
    }

    @Test
    @DisplayName("위험 알림 받은 날 + 알림 없던 높은 날이면 보낸다(위험 알림이 같은 날 다른 대화에서 나가도 그 날은 알림 받은 날)")
    void alertedPlusFreshDayQualifies() {
        CompanionSession alertedOtherSession = alerted(turn(TODAY.minusDays(1), CompanionSlot.S18, null));
        List<CompanionSession> onlyAlertedDay = List.of(
                turn(TODAY.minusDays(1), CompanionSlot.S09, "0.98"), alertedOtherSession,
                alerted(turn(TODAY, CompanionSlot.S09, "0.99")));
        List<CompanionSession> withFreshDay = List.of(
                alerted(turn(TODAY.minusDays(4), CompanionSlot.S09, "0.995")),
                turn(TODAY, CompanionSlot.S09, "0.98"));

        assertThat(CompanionRiskPattern.qualifyingDays(onlyAlertedDay, TODAY, config)).isEmpty();
        assertThat(CompanionRiskPattern.qualifyingDays(withFreshDay, TODAY, config)).hasValue(2);
    }

    @Test
    @DisplayName("진행 중(OPEN)·테스트 대화도 똑같이 센다")
    void openAndTestSessionsCount() {
        CompanionSession open = CompanionTestSessions.open(W, TODAY, CompanionSlot.S12);
        open.recordTurn(1, false, new BigDecimal("0.98"), CompanionTestSessions.at(TODAY, CompanionSlot.S12, 2));
        CompanionSession test = turn(TODAY.minusDays(1), CompanionSlot.S09, "0.98");
        ReflectionTestUtils.setField(test, "test", true);

        assertThat(CompanionRiskPattern.qualifyingDays(List.of(open, test), TODAY, config)).hasValue(2);
    }

    @Test
    @DisplayName("AI riskProbability 읽기: 숫자 0~1만, 넷째 자리 내림(기준을 반올림으로 넘지 않음), 그 밖은 null")
    void parsesRiskProbability() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        assertThat(CompanionService.riskProbability(mapper.readTree("{\"riskProbability\":0.96999}")))
                .isEqualByComparingTo("0.9699");
        assertThat(CompanionService.riskProbability(mapper.readTree("{\"riskProbability\":1}")))
                .isEqualByComparingTo("1");
        for (String json : List.of("{}", "{\"riskProbability\":null}", "{\"riskProbability\":\"0.99\"}",
                "{\"riskProbability\":1.2}", "{\"riskProbability\":-0.1}")) {
            assertThat(CompanionService.riskProbability(mapper.readTree(json))).as(json).isNull();
        }
    }

    static CompanionSession turn(LocalDate date, CompanionSlot slot, String probability) {
        CompanionSession session = CompanionTestSessions.finished(W, date, slot, null, 3);
        ReflectionTestUtils.setField(session, "maxRiskProb", probability == null ? null : new BigDecimal(probability));
        return session;
    }

    static CompanionSession alerted(CompanionSession session) {
        ReflectionTestUtils.setField(session, "riskNotifiedAt", session.getStartedAt());
        return session;
    }
}
