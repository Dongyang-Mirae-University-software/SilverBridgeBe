package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties.TriggerMode;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AnomalyJudge 단위 테스트.
 *
 * 핵심 정책 두 가지를 고정한다.
 *  ① DANGER 모드(기본) — AI가 채운 danger 플래그만이 판정 근거다. 신뢰도가 아무리 높아도 danger=false면 무시.
 *  ② CONFIDENCE 모드(폴백) — danger를 보지 않고 백엔드 임계로 판정한다(AI danger 미배포 공백 대응).
 * 두 모드 공통으로 normal·unknown은 이상감지가 아니다.
 */
class AnomalyJudgeTest {

    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    private AnomalyProperties properties;
    private AnomalyJudge judge;

    @BeforeEach
    void setUp() {
        properties = new AnomalyProperties();
        properties.setConfidenceThreshold(0.6);
        judge = new AnomalyJudge(properties);
    }

    private AnomalySignal signal(DetectedType type, double confidence, boolean danger) {
        return new AnomalySignal(SESSION_ID, type, confidence, danger, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("DANGER 모드 (기본) — AI 판정만 신뢰")
    class DangerMode {

        @BeforeEach
        void useDangerMode() {
            properties.setTriggerMode(TriggerMode.DANGER);
        }

        @Test
        @DisplayName("danger=true 이면 이상감지로 인정한다")
        void dangerTrue_isAnomaly() {
            assertThat(judge.isAnomaly(signal(DetectedType.FIRE, 0.84, true))).isTrue();
        }

        @Test
        @DisplayName("danger=false 이면 신뢰도가 임계를 넘어도 무시한다 (판정 책임은 AI)")
        void dangerFalse_ignoredEvenWithHighConfidence() {
            assertThat(judge.isAnomaly(signal(DetectedType.FIRE, 0.95, false))).isFalse();
        }

        @Test
        @DisplayName("normal·unknown 은 danger=true 여도 무시한다")
        void nonDetectableType_ignored() {
            assertThat(judge.isAnomaly(signal(DetectedType.NORMAL, 0.9, true))).isFalse();
            assertThat(judge.isAnomaly(signal(DetectedType.UNKNOWN, 0.9, true))).isFalse();
        }

        @Test
        @DisplayName("라이브 미탑재 종류(fall·weapon)는 아직 이상감지 대상이 아니다")
        void notYetLiveTypes_ignored() {
            assertThat(judge.isAnomaly(signal(DetectedType.FALL, 0.9, true))).isFalse();
            assertThat(judge.isAnomaly(signal(DetectedType.WEAPON, 0.9, true))).isFalse();
        }
    }

    @Nested
    @DisplayName("CONFIDENCE 모드 (폴백) — 백엔드 임계로 판정")
    class ConfidenceMode {

        @BeforeEach
        void useConfidenceMode() {
            properties.setTriggerMode(TriggerMode.CONFIDENCE);
        }

        @Test
        @DisplayName("임계 이상이면 danger=false 여도 이상감지로 인정한다 (AI danger 미배포 공백 대응)")
        void aboveThreshold_isAnomalyEvenWhenDangerFalse() {
            assertThat(judge.isAnomaly(signal(DetectedType.SMOKE, 0.6, false))).isTrue();
            assertThat(judge.isAnomaly(signal(DetectedType.FIRE, 0.84, false))).isTrue();
        }

        @Test
        @DisplayName("임계 미만이면 무시한다")
        void belowThreshold_ignored() {
            assertThat(judge.isAnomaly(signal(DetectedType.FIRE, 0.59, false))).isFalse();
        }

        @Test
        @DisplayName("normal·unknown 은 신뢰도가 높아도 무시한다")
        void nonDetectableType_ignored() {
            assertThat(judge.isAnomaly(signal(DetectedType.NORMAL, 0.99, false))).isFalse();
            assertThat(judge.isAnomaly(signal(DetectedType.UNKNOWN, 0.99, false))).isFalse();
        }
    }
}
