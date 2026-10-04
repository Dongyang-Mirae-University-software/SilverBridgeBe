package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 클립 청소 - 조건부 삭제(번복된 클립 보호), 행 → 파일 순서, 고아 판정의 1시간 유예. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnomalyClipCleanupSchedulerTest {

    @TempDir
    Path root;

    @Mock private AnomalyClipRepository clipRepository;
    @Mock private CameraService cameraService;

    private AnomalyClipStorage storage;
    private AnomalyClipCleanupScheduler scheduler;
    private final OffsetDateTime now = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();
        properties.getClip().setStorageDir(root.toString());
        storage = new AnomalyClipStorage(properties);
        scheduler = new AnomalyClipCleanupScheduler(clipRepository, storage, cameraService, properties);
    }

    private AnomalyClip clip(long id, String fileName, AnomalyClipStatus status) {
        AnomalyClip clip = AnomalyClip.builder().incidentId(37L).wardId("WD0001").sessionId("s1")
                .fileName(fileName).sizeBytes(1).detectedAt(now).status(status)
                .hiddenAt(status == AnomalyClipStatus.HIDDEN ? now.minusHours(30) : null).build();
        ReflectionTestUtils.setField(clip, "id", id);
        return clip;
    }

    private String file() {
        return storage.write(new byte[]{1});
    }

    private void age(String name, long hours) throws IOException {
        Files.setLastModifiedTime(root.resolve(name), FileTime.from(Instant.now().minus(hours, ChronoUnit.HOURS)));
    }

    @Test
    @DisplayName("유예가 지난 비공개 클립은 행과 파일을 지운다 - 그 사이 번복돼 조건부 삭제가 0이면 파일을 남긴다")
    void 비공개_정리() {
        String gone = file();
        String restored = file();
        when(clipRepository.findByStatusAndHiddenAtBefore(eq(AnomalyClipStatus.HIDDEN), any(), any()))
                .thenReturn(List.of(clip(1, gone, AnomalyClipStatus.HIDDEN), clip(2, restored, AnomalyClipStatus.HIDDEN)))
                .thenReturn(List.of());
        when(clipRepository.deleteHiddenById(eq(1L), any())).thenReturn(1);
        when(clipRepository.deleteHiddenById(eq(2L), any())).thenReturn(0);

        assertThat(scheduler.purgeHidden(now)).isEqualTo(1);

        assertThat(storage.find(gone)).isEmpty();
        assertThat(storage.find(restored)).isPresent();
    }

    @Test
    @DisplayName("비공개 기준 시각은 now - 24시간이다")
    void 비공개_기준() {
        when(clipRepository.findByStatusAndHiddenAtBefore(any(), any(), any())).thenReturn(List.of());

        scheduler.purgeHidden(now);

        verify(clipRepository).findByStatusAndHiddenAtBefore(eq(AnomalyClipStatus.HIDDEN), eq(now.minusHours(24)), any());
    }

    @Test
    @DisplayName("보관 기간(30일)이 지난 클립은 상태와 무관하게 지운다")
    void 보관만료() {
        String expired = file();
        when(clipRepository.findByCreatedAtBefore(eq(now.minusDays(30)), any()))
                .thenReturn(List.of(clip(3, expired, AnomalyClipStatus.VISIBLE)))
                .thenReturn(List.of());
        when(clipRepository.deleteOneById(3L)).thenReturn(1);

        assertThat(scheduler.purgeExpired(now)).isEqualTo(1);
        assertThat(storage.find(expired)).isEmpty();
    }

    @Test
    @DisplayName("카메라가 사라진 세션의 클립을 지운다")
    void 카메라_사라짐() {
        String orphan = file();
        when(clipRepository.findDistinctSessionIds()).thenReturn(List.of("alive", "gone"));
        when(cameraService.findLabelsBySessionIds(List.of("alive", "gone"))).thenReturn(Map.of("alive", "거실"));
        when(clipRepository.findBySessionIdIn(List.of("gone"))).thenReturn(List.of(clip(4, orphan, AnomalyClipStatus.VISIBLE)));
        when(clipRepository.deleteOneById(4L)).thenReturn(1);

        assertThat(scheduler.purgeCameraGone()).isEqualTo(1);
        assertThat(storage.find(orphan)).isEmpty();
    }

    @Test
    @DisplayName("파일이 없는 행은 지우고, 파일이 있는 행은 둔다")
    void 파일없는_행() {
        String present = file();
        when(clipRepository.findByCreatedAtBeforeOrderByIdAsc(any())).thenReturn(List.of(
                clip(5, present, AnomalyClipStatus.VISIBLE),
                clip(6, "00000000-0000-0000-0000-00000000dead.webm", AnomalyClipStatus.VISIBLE)));
        when(clipRepository.deleteOneById(6L)).thenReturn(1);

        assertThat(scheduler.purgeMissingFiles(now)).isEqualTo(1);
        verify(clipRepository, never()).deleteOneById(5L);
    }

    @Test
    @DisplayName("행 없는 파일·임시 파일은 1시간이 지난 것만 지운다(저장 중인 클립 보호)")
    void 고아_파일() throws IOException {
        String known = file();
        String orphanOld = file();
        String orphanFresh = file();
        Files.write(root.resolve(".tmp-1.part"), new byte[]{1});
        age(known, 3);
        age(orphanOld, 3);
        age(".tmp-1.part", 3);
        when(clipRepository.findAllFileNames()).thenReturn(List.of(known));

        assertThat(scheduler.purgeOrphanFiles(Instant.now())).isEqualTo(2);

        assertThat(storage.find(known)).isPresent();
        assertThat(storage.find(orphanOld)).isEmpty();
        assertThat(storage.find(orphanFresh)).as("방금 쓴 파일은 아직 행이 없을 수 있다").isPresent();
        assertThat(root.resolve(".tmp-1.part")).doesNotExist();
    }

    @Test
    @DisplayName("한 단계가 실패해도 나머지 단계는 돈다")
    void 단계_격리() {
        when(clipRepository.findByStatusAndHiddenAtBefore(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        when(clipRepository.findByCreatedAtBefore(any(), any())).thenReturn(List.of());

        scheduler.cleanup();

        verify(clipRepository).findByCreatedAtBefore(any(), any());
        verify(clipRepository).findByCreatedAtBeforeOrderByIdAsc(any());
        verify(clipRepository, never()).deleteOneById(anyLong());
    }

    @Test
    @DisplayName("고아 파일 회수는 매시 작업이 맡는다(L-3 - 스윕 purge로 남은 탈퇴자 파일을 하루 넘게 두지 않는다)")
    void 고아파일_매시() throws IOException {
        String orphan = file();
        age(orphan, 3);
        when(clipRepository.findAllFileNames()).thenReturn(List.of());

        scheduler.cleanup();
        assertThat(storage.find(orphan)).as("일일 작업은 고아 파일을 보지 않는다").isPresent();

        scheduler.cleanupOrphanFiles();
        assertThat(storage.find(orphan)).isEmpty();
    }
}
