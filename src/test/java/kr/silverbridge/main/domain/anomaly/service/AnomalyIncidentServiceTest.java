package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 상황(incident) 묶음 규칙 검증.
 *
 * <p>묶음 기준은 "판정을 몇 번 하게 되는가"와 "이상감지 건수가 몇 건으로 보이는가"를 동시에 정하는 규칙이라,
 * 경계값을 실제 시각에 의존하지 않는 리터럴로 고정한다(복약 자정 유예 창 테스트와 같은 방식).</p>
 */
@ExtendWith(MockitoExtension.class)
class AnomalyIncidentServiceTest {

    private static final String WARD_ID = "WD0001";
    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    /** KST 기준 시각. 자정 판정이 서버 타임존에 좌우되지 않는지 보려면 오프셋을 명시해야 한다. */
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock private AnomalyIncidentRepository anomalyIncidentRepository;

    private AnomalyIncidentService incidentService;

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();   // 기본 10분
        incidentService = new AnomalyIncidentService(anomalyIncidentRepository, properties);
    }

    private static OffsetDateTime kst(int hour, int minute) {
        return OffsetDateTime.of(2026, 8, 31, hour, minute, 0, 0, KST);
    }

    private static AnomalyIncident existing(OffsetDateTime lastDetectedAt) {
        return AnomalyIncident.builder()
                .wardId(WARD_ID).sessionId(SESSION_ID).detectedType(DetectedType.FIRE)
                .detectedAt(lastDetectedAt).confidence(0.7)
                .build();
    }

    @Nested
    @DisplayName("승계 판정 규칙 (isContinuation)")
    class Continuation {

        @Test
        @DisplayName("기준 시간 이내면 승계하고, 경계값(정확히 10분)도 승계다")
        void withinWindow_continues() {
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 0), kst(21, 0), 10)).isTrue();
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 0), kst(21, 9), 10)).isTrue();
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 0), kst(21, 10), 10)).isTrue();
        }

        @Test
        @DisplayName("기준 시간을 넘으면 별개 상황이다")
        void beyondWindow_startsNew() {
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 0), kst(21, 11), 10)).isFalse();
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 0), kst(23, 0), 10)).isFalse();
        }

        @Test
        @DisplayName("KST 자정을 넘기면 기준 시간 이내라도 새 상황이다 (일자별 통계 기준을 지킨다)")
        void acrossKstMidnight_startsNew() {
            OffsetDateTime lastNight = OffsetDateTime.of(2026, 8, 31, 23, 55, 0, 0, KST);
            OffsetDateTime afterMidnight = OffsetDateTime.of(2026, 9, 1, 0, 3, 0, 0, KST);   // 8분 뒤

            assertThat(AnomalyIncidentService.isContinuation(lastNight, afterMidnight, 10)).isFalse();
        }

        @Test
        @DisplayName("자정 판정은 KST 기준이다 — UTC로 날짜가 갈리는 시각도 같은 날로 본다")
        void midnightIsJudgedInKst() {
            // 둘 다 KST 2026-08-31이지만 UTC로는 08-30(14:55Z) / 08-31(15:03Z)로 갈린다
            OffsetDateTime before = OffsetDateTime.of(2026, 8, 31, 23, 55, 0, 0, ZoneOffset.ofHours(9));
            OffsetDateTime after = OffsetDateTime.of(2026, 8, 31, 15, 3, 0, 0, ZoneOffset.UTC);   // = KST 09-01 00:03

            // after는 KST로 다음 날이므로 끊긴다(오프셋 표기가 달라도 판정은 KST 날짜로 한다)
            assertThat(AnomalyIncidentService.isContinuation(before, after, 10)).isFalse();

            OffsetDateTime sameKstDay = OffsetDateTime.of(2026, 8, 31, 14, 58, 0, 0, ZoneOffset.UTC); // = KST 23:58
            assertThat(AnomalyIncidentService.isContinuation(before, sameKstDay, 10)).isTrue();
        }

        @Test
        @DisplayName("새 감지가 직전보다 이르면(시각 역행) 잇지 않는다")
        void goingBackwards_startsNew() {
            assertThat(AnomalyIncidentService.isContinuation(kst(21, 10), kst(21, 9), 10)).isFalse();
        }
    }

    @Test
    @DisplayName("기준 시간 이내의 연속 감지는 기존 상황에 승계된다 (새 행을 만들지 않는다)")
    void continuesExistingIncident() {
        AnomalyIncident previous = existing(kst(21, 0));
        when(anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, SESSION_ID, DetectedType.FIRE))
                .thenReturn(Optional.of(previous));

        AnomalyIncident resolved = incidentService.resolveIncident(
                WARD_ID, SESSION_ID, DetectedType.FIRE, kst(21, 5), 0.91);

        assertThat(resolved).isSameAs(previous);
        assertThat(resolved.getEventCount()).isEqualTo(2);
        assertThat(resolved.getLastDetectedAt()).isEqualTo(kst(21, 5));
        assertThat(resolved.getStartedAt()).isEqualTo(kst(21, 0));       // 시작 시각은 유지
        assertThat(resolved.getMaxConfidence()).isEqualTo(0.91);          // 최고값 갱신
        verify(anomalyIncidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("승계 시 최고 신뢰도는 낮아지지 않는다 (가장 위험해 보였던 순간이 판단 근거다)")
    void maxConfidenceNeverDecreases() {
        AnomalyIncident previous = existing(kst(21, 0));                  // 0.7
        when(anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, SESSION_ID, DetectedType.FIRE))
                .thenReturn(Optional.of(previous));

        AnomalyIncident resolved = incidentService.resolveIncident(
                WARD_ID, SESSION_ID, DetectedType.FIRE, kst(21, 2), 0.42);

        assertThat(resolved.getMaxConfidence()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("직전 상황이 없으면 새 상황을 PENDING으로 연다")
    void opensNewIncidentWhenNonePrevious() {
        when(anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, SESSION_ID, DetectedType.SMOKE))
                .thenReturn(Optional.empty());
        when(anomalyIncidentRepository.save(any(AnomalyIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        AnomalyIncident opened = incidentService.resolveIncident(
                WARD_ID, SESSION_ID, DetectedType.SMOKE, kst(9, 30), 0.66);

        ArgumentCaptor<AnomalyIncident> captor = ArgumentCaptor.forClass(AnomalyIncident.class);
        verify(anomalyIncidentRepository).save(captor.capture());
        AnomalyIncident saved = captor.getValue();
        assertThat(saved.getWardId()).isEqualTo(WARD_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getDetectedType()).isEqualTo(DetectedType.SMOKE);
        assertThat(saved.getStartedAt()).isEqualTo(kst(9, 30));
        assertThat(saved.getLastDetectedAt()).isEqualTo(kst(9, 30));
        assertThat(saved.getEventCount()).isEqualTo(1);
        assertThat(saved.getReviewStatus()).isEqualTo(AnomalyReviewStatus.PENDING);
        assertThat(saved.getResolvedBy()).isNull();
        assertThat(opened).isSameAs(saved);
    }

    @Test
    @DisplayName("기준 시간을 넘긴 감지는 새 상황을 연다")
    void opensNewIncidentAfterWindow() {
        AnomalyIncident previous = existing(kst(21, 0));
        when(anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, SESSION_ID, DetectedType.FIRE))
                .thenReturn(Optional.of(previous));
        when(anomalyIncidentRepository.save(any(AnomalyIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        AnomalyIncident resolved = incidentService.resolveIncident(
                WARD_ID, SESSION_ID, DetectedType.FIRE, kst(21, 30), 0.8);

        assertThat(resolved).isNotSameAs(previous);
        assertThat(previous.getEventCount()).isEqualTo(1);               // 기존 상황은 그대로
        verify(anomalyIncidentRepository).save(any(AnomalyIncident.class));
    }

    @Test
    @DisplayName("다른 카메라·다른 유형은 섞이지 않는다 (조회 자체가 세 값으로 좁혀진다)")
    void doesNotMergeAcrossCameraOrType() {
        String otherSession = "ward_zZ9xY_p1q";
        when(anomalyIncidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, otherSession, DetectedType.FIRE))
                .thenReturn(Optional.empty());
        when(anomalyIncidentRepository.save(any(AnomalyIncident.class))).thenAnswer(inv -> inv.getArgument(0));

        incidentService.resolveIncident(WARD_ID, otherSession, DetectedType.FIRE, kst(21, 1), 0.7);

        // 거실(SESSION_ID) 상황을 끌어오지 않았음을 조회 인자로 확인한다
        verify(anomalyIncidentRepository)
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, otherSession, DetectedType.FIRE);
        verify(anomalyIncidentRepository, never())
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(WARD_ID, SESSION_ID, DetectedType.FIRE);
        verify(anomalyIncidentRepository).save(any(AnomalyIncident.class));
    }
}
