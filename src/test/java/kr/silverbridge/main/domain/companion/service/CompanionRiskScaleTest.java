package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.config.CompanionProperties;
import kr.silverbridge.main.domain.companion.entity.CompanionRiskProbability;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import kr.silverbridge.main.domain.companion.event.CompanionRiskPatternCheckEvent;
import kr.silverbridge.main.domain.companion.repository.CompanionSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 위험 확률 눈금(2026-11-15, AI #296 {@code riskModel}): v9(기본 모델)과 위험 판단 어댑터(LoRA) 확률을 따로 기록하고
 * 여러 날 누적은 눈금별 기준(v9 0.97 · 어댑터 0.3)으로 본다.
 */
class CompanionRiskScaleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 15, 9, 10, 0, 0, ZoneOffset.ofHours(9));
    private static final LocalDate TODAY = NOW.toLocalDate();

    private static CompanionRiskProbability parse(String json) throws Exception {
        return CompanionService.turnRisk(MAPPER.readTree(json));
    }

    @Test
    @DisplayName("riskModel 없음·null·\"v9\"는 v9 눈금, \"v9+…\"는 어댑터 눈금, 모르는 값·숫자는 기록하지 않는다")
    void parsesScaleFromRiskModel() throws Exception {
        assertThat(parse("{\"riskProbability\":0.5}")).isEqualTo(new CompanionRiskProbability(new BigDecimal("0.5000"), false));
        assertThat(parse("{\"riskProbability\":0.5,\"riskModel\":null}").lora()).isFalse();
        assertThat(parse("{\"riskProbability\":0.5,\"riskModel\":\"v9\"}").lora()).isFalse();
        CompanionRiskProbability lora = parse("{\"riskProbability\":0.31239,\"riskModel\":\"v9+lora-risk-r1\"}");
        assertThat(lora.lora()).isTrue();
        assertThat(lora.value()).isEqualByComparingTo("0.3123");
        for (String json : List.of("{\"riskProbability\":0.5,\"riskModel\":\"v4\"}",
                "{\"riskProbability\":0.5,\"riskModel\":\"V9+lora\"}",
                "{\"riskProbability\":0.5,\"riskModel\":7}",
                "{\"riskModel\":\"v9+lora-risk-r1\"}",
                "{\"riskProbability\":null,\"riskModel\":\"v9\"}")) {
            assertThat(parse(json)).as(json).isNull();
        }
    }

    @Test
    @DisplayName("대화의 최댓값은 눈금별 칸에 따로 남는다 - 어댑터 0.99가 v9 칸을 올리지 않는다")
    void keepsMaximaPerScale() {
        CompanionSession session = open(1L);
        session.recordTurn(1, false, CompanionRiskProbability.v9(new BigDecimal("0.4000")), NOW);
        session.recordTurn(2, false, CompanionRiskProbability.lora(new BigDecimal("0.9900")), NOW);
        session.recordTurn(3, false, CompanionRiskProbability.lora(new BigDecimal("0.2000")), NOW);
        session.recordTurn(4, false, CompanionRiskProbability.v9(new BigDecimal("0.3000")), NOW);
        session.recordTurn(5, false, (CompanionRiskProbability) null, NOW);

        assertThat(session.getMaxRiskProb()).isEqualByComparingTo("0.4000");
        assertThat(session.getMaxRiskProbLora()).isEqualByComparingTo("0.9900");
        assertThat(session.getTurnCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("기존 BigDecimal 기록은 v9 칸으로 간다(AI #296 이전 응답과 같은 동작)")
    void bigDecimalOverloadIsV9() {
        CompanionSession session = open(2L);
        session.recordTurn(1, false, new BigDecimal("0.9800"), NOW);

        assertThat(session.getMaxRiskProb()).isEqualByComparingTo("0.9800");
        assertThat(session.getMaxRiskProbLora()).isNull();
    }

    @Test
    @DisplayName("높은 대화: 어댑터 눈금은 0.3 이상(같아도), v9 눈금은 0.97 이상 - 서로의 기준을 쓰지 않는다")
    void isHighUsesScaleThreshold() {
        CompanionProperties.RiskPattern config = new CompanionProperties().getRiskPattern();

        assertThat(CompanionRiskPattern.isHigh(session(null, "0.3000"), config)).isTrue();
        assertThat(CompanionRiskPattern.isHigh(session(null, "0.2999"), config)).isFalse();
        assertThat(CompanionRiskPattern.isHigh(session("0.9000", null), config)).isFalse(); // v9 0.9는 0.3 이상이어도 낮음
        assertThat(CompanionRiskPattern.isHigh(session("0.9700", "0.0100"), config)).isTrue();
        assertThat(CompanionRiskPattern.isHigh(session(null, null), config)).isFalse();
    }

    @Test
    @DisplayName("한 날은 v9 대화로, 다른 날은 어댑터 대화로 높아도 높은 날 2일로 센다(눈금이 섞인 주)")
    void mixedScaleDaysCount() {
        CompanionProperties.RiskPattern config = new CompanionProperties().getRiskPattern();
        CompanionSession v9Day = CompanionRiskPatternTest.turn(TODAY.minusDays(3), CompanionSlot.S09, "0.9800");
        CompanionSession loraDay = CompanionRiskPatternTest.turn(TODAY, CompanionSlot.S15, null);
        ReflectionTestUtils.setField(loraDay, "maxRiskProbLora", new BigDecimal("0.4500"));
        CompanionSession lowLora = CompanionRiskPatternTest.turn(TODAY.minusDays(1), CompanionSlot.S12, null);
        ReflectionTestUtils.setField(lowLora, "maxRiskProbLora", new BigDecimal("0.1000"));

        assertThat(CompanionRiskPattern.qualifyingDays(List.of(v9Day, loraDay, lowLora), TODAY, config)).hasValue(2);
    }

    @Test
    @DisplayName("턴 판정 이벤트: 어댑터 확률은 0.3 이상이면 내고, 같은 숫자의 v9 확률은 내지 않는다")
    void writerPublishesByScale() {
        CompanionSessionRepository repository = mock(CompanionSessionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CompanionProperties properties = new CompanionProperties();
        properties.getRiskPattern().setEnabled(true);
        CompanionSessionWriter writer = new CompanionSessionWriter(repository, publisher, properties,
                mock(CompanionTurnRecorder.class));
        when(repository.findById(3L)).thenReturn(Optional.of(open(3L)));

        writer.recordTurn(3L, 1, false, CompanionRiskProbability.v9(new BigDecimal("0.4000")),
                CompanionTurnRecorder.TurnContent.NONE, NOW);
        writer.recordTurn(3L, 2, false, CompanionRiskProbability.lora(new BigDecimal("0.2999")),
                CompanionTurnRecorder.TurnContent.NONE, NOW);
        verify(publisher, never()).publishEvent(any(CompanionRiskPatternCheckEvent.class));

        writer.recordTurn(3L, 3, false, CompanionRiskProbability.lora(new BigDecimal("0.3000")),
                CompanionTurnRecorder.TurnContent.NONE, NOW);
        verify(publisher).publishEvent(new CompanionRiskPatternCheckEvent("CW0001", TODAY));
    }

    @Test
    @DisplayName("기본 설정: 어댑터 기준 0.3, v9 기준 0.97 그대로, 스위치 꺼짐")
    void defaults() {
        CompanionProperties.RiskPattern config = new CompanionProperties().getRiskPattern();
        assertThat(config.getLoraThreshold()).isEqualByComparingTo("0.3");
        assertThat(config.getThreshold()).isEqualByComparingTo("0.97");
        assertThat(config.isEnabled()).isFalse();
    }

    private static CompanionSession session(String v9, String lora) {
        CompanionSession session = CompanionRiskPatternTest.turn(TODAY, CompanionSlot.S09, v9);
        ReflectionTestUtils.setField(session, "maxRiskProbLora", lora == null ? null : new BigDecimal(lora));
        return session;
    }

    private static CompanionSession open(long id) {
        CompanionSession session = CompanionSession.claim("CW0001", TODAY, CompanionSlot.S09, 1L, NOW);
        ReflectionTestUtils.setField(session, "id", id);
        session.attachDialogue("d-" + id, 8, NOW);
        return session;
    }
}
