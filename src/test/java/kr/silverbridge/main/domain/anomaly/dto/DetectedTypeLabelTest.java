package kr.silverbridge.main.domain.anomaly.dto;

import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectedTypeLabelTest {

    @Test
    @DisplayName("주격 조사는 받침에 따라 가/이: 화재가 · 흉기가 · 낙상이")
    void subjectParticle() {
        assertThat(DetectedTypeLabel.withSubjectParticle(DetectedType.FIRE)).isEqualTo("화재가");
        assertThat(DetectedTypeLabel.withSubjectParticle(DetectedType.WEAPON)).isEqualTo("흉기가");
        assertThat(DetectedTypeLabel.withSubjectParticle(DetectedType.FALL)).isEqualTo("낙상이");
    }
}
