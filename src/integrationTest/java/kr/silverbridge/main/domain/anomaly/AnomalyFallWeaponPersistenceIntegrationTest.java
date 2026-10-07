package kr.silverbridge.main.domain.anomaly;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낙상·흉기 이력이 실제 PostgreSQL에 저장되는지(2026-10-07 점검 테스트 제안 3).
 *
 * <p>화재만 라이브이던 시절의 스키마가 다른 종류를 막고 있지 않은지(과거에는 {@code detected_type} CHECK가 있었다 -
 * V17이 제거) 확인하고, 같은 카메라의 종류가 다르면 별개 상황으로 조회됨을 고정한다. 나중에 누가 CHECK를 다시
 * 추가하면 시연 중 저장이 조용히 실패하므로 마이그레이션 단계에서 잡는다.</p>
 */
class AnomalyFallWeaponPersistenceIntegrationTest extends PostgresIntegrationTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 6, 21, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired private UserRepository userRepository;
    @Autowired private AnomalyEventRepository eventRepository;
    @Autowired private AnomalyIncidentRepository incidentRepository;

    @Test
    @DisplayName("낙상·흉기 이벤트와 상황이 저장되고, 같은 카메라라도 종류별로 다른 상황으로 조회된다")
    void 낙상_흉기_저장과_종류별_상황() {
        userRepository.save(TestData.user("WD0001", "김영희", Role.WARD));

        for (DetectedType type : new DetectedType[] {DetectedType.FIRE, DetectedType.FALL, DetectedType.WEAPON}) {
            AnomalyIncident incident = incidentRepository.save(AnomalyIncident.builder()
                    .wardId("WD0001").sessionId("sess-living").detectedType(type)
                    .detectedAt(AT).confidence(0.9).build());
            eventRepository.save(AnomalyEvent.builder()
                    .wardId("WD0001").sessionId("sess-living").detectedType(type)
                    .confidence(0.9).danger(true).detectedAt(AT).incidentId(incident.getId()).build());
        }
        eventRepository.flush();

        assertThat(eventRepository.findByWardIdOrderByCreatedAtDesc("WD0001"))
                .extracting(AnomalyEvent::getDetectedType)
                .containsExactlyInAnyOrder(DetectedType.FIRE, DetectedType.FALL, DetectedType.WEAPON);

        AnomalyIncident fall = incidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(
                        "WD0001", "sess-living", DetectedType.FALL).orElseThrow();
        AnomalyIncident weapon = incidentRepository
                .findFirstByWardIdAndSessionIdAndDetectedTypeOrderByLastDetectedAtDesc(
                        "WD0001", "sess-living", DetectedType.WEAPON).orElseThrow();
        assertThat(fall.getId()).isNotEqualTo(weapon.getId());
    }
}
