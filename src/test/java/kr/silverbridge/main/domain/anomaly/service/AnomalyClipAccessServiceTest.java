package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클립 열람 인가 - 보호자는 ACTIVE 연결만, 피보호자는 본인 + ACTIVE 연결 1건 이상, 비공개·없음은 404.
 * PENDING 연결은 {@code isActiveConnection=false}로 들어온다(인가 메서드가 PENDING을 걸러낸다).
 */
@ExtendWith(MockitoExtension.class)
class AnomalyClipAccessServiceTest {

    private static final String GUARDIAN = "GD0001";
    private static final String WARD = "WD0001";
    private static final String OTHER_WARD = "WD0002";
    private static final Long INCIDENT_ID = 37L;
    private static final Long CLIP_ID = 101L;

    @Mock private AnomalyClipService clipService;
    @Mock private AnomalyClipStorage storage;
    @Mock private AnomalyIncidentRepository incidentRepository;
    @Mock private ConnectionService connectionService;

    private AnomalyClipAccessService service;

    @BeforeEach
    void setUp() {
        service = new AnomalyClipAccessService(clipService, storage, incidentRepository, connectionService);
    }

    private static AnomalyIncident incidentOf(String wardId) {
        return AnomalyIncident.builder().wardId(wardId).sessionId("s1").detectedType(DetectedType.FIRE)
                .detectedAt(OffsetDateTime.now()).confidence(0.9).build();
    }

    private static AnomalyClip clipOf(String wardId) {
        AnomalyClip clip = AnomalyClip.builder().incidentId(INCIDENT_ID).wardId(wardId).sessionId("s1")
                .fileName("00000000-0000-0000-0000-000000000001.webm").sizeBytes(10)
                .detectedAt(OffsetDateTime.now()).status(AnomalyClipStatus.VISIBLE).build();
        ReflectionTestUtils.setField(clip, "id", CLIP_ID);
        return clip;
    }

    private static void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    @Nested
    @DisplayName("보호자")
    class Guardian {

        @Test
        @DisplayName("ACTIVE 연결 피보호자의 클립 목록·파일을 받는다")
        void 연결됨_허용() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incidentOf(WARD)));
            when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
            when(clipService.visibleClipsOf(INCIDENT_ID)).thenReturn(List.of(clipOf(WARD)));
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(WARD)));
            when(storage.find("00000000-0000-0000-0000-000000000001.webm")).thenReturn(Optional.of(Path.of("/x")));

            assertThat(service.guardianClips(GUARDIAN, INCIDENT_ID)).singleElement()
                    .satisfies(item -> assertThat(item.clipId()).isEqualTo(CLIP_ID));
            assertThat(service.guardianFile(GUARDIAN, CLIP_ID).clipId()).isEqualTo(CLIP_ID);
        }

        @Test
        @DisplayName("연결 없음·PENDING이면 403 ANOMALY_NOT_AUTHORIZED - 목록·파일 모두")
        void 미연결_거부() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incidentOf(WARD)));
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(WARD)));
            when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

            assertError(() -> service.guardianClips(GUARDIAN, INCIDENT_ID), ErrorCode.ANOMALY_NOT_AUTHORIZED);
            assertError(() -> service.guardianFile(GUARDIAN, CLIP_ID), ErrorCode.ANOMALY_NOT_AUTHORIZED);
            verify(clipService, never()).visibleClipsOf(anyLong());
            verify(storage, never()).find(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("없는 상황 404, 없음·비공개·만료 클립 404")
        void 없음() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.empty());
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.empty());

            assertError(() -> service.guardianClips(GUARDIAN, INCIDENT_ID), ErrorCode.ANOMALY_INCIDENT_NOT_FOUND);
            assertError(() -> service.guardianFile(GUARDIAN, CLIP_ID), ErrorCode.ANOMALY_CLIP_NOT_FOUND);
        }

        @Test
        @DisplayName("행은 있으나 파일이 없으면 404")
        void 파일_없음() {
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(WARD)));
            when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
            when(storage.find("00000000-0000-0000-0000-000000000001.webm")).thenReturn(Optional.empty());

            assertError(() -> service.guardianFile(GUARDIAN, CLIP_ID), ErrorCode.ANOMALY_CLIP_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("피보호자 본인")
    class Ward {

        @Test
        @DisplayName("본인 집 클립 + ACTIVE 보호자 1명 이상이면 받는다")
        void 본인_연결있음_허용() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incidentOf(WARD)));
            when(connectionService.getActiveGuardianIds(WARD)).thenReturn(List.of(GUARDIAN));
            when(clipService.visibleClipsOf(INCIDENT_ID)).thenReturn(List.of(clipOf(WARD)));
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(WARD)));
            when(storage.find("00000000-0000-0000-0000-000000000001.webm")).thenReturn(Optional.of(Path.of("/x")));

            assertThat(service.wardClips(WARD, INCIDENT_ID)).hasSize(1);
            assertThat(service.wardFile(WARD, CLIP_ID).clipId()).isEqualTo(CLIP_ID);
        }

        @Test
        @DisplayName("다른 피보호자의 클립이면 403 ANOMALY_CLIP_NOT_OWNED")
        void 타인_거부() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incidentOf(OTHER_WARD)));
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(OTHER_WARD)));

            assertError(() -> service.wardClips(WARD, INCIDENT_ID), ErrorCode.ANOMALY_CLIP_NOT_OWNED);
            assertError(() -> service.wardFile(WARD, CLIP_ID), ErrorCode.ANOMALY_CLIP_NOT_OWNED);
            verify(connectionService, never()).getActiveGuardianIds(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("ACTIVE 연결이 0건이면 본인 클립도 404(비공개, 파일은 지우지 않는다)")
        void 연결없음_비공개() {
            when(incidentRepository.findById(INCIDENT_ID)).thenReturn(Optional.of(incidentOf(WARD)));
            when(clipService.findViewable(CLIP_ID)).thenReturn(Optional.of(clipOf(WARD)));
            when(connectionService.getActiveGuardianIds(WARD)).thenReturn(List.of());

            assertError(() -> service.wardClips(WARD, INCIDENT_ID), ErrorCode.ANOMALY_CLIP_NOT_FOUND);
            assertError(() -> service.wardFile(WARD, CLIP_ID), ErrorCode.ANOMALY_CLIP_NOT_FOUND);
            verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
        }
    }
}
