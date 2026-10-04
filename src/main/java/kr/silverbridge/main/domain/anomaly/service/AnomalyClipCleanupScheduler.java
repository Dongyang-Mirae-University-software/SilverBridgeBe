package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.service.AnomalyClipStorage.StoredFile;
import kr.silverbridge.main.domain.camera.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 이상감지 클립 청소. 하루 한 번(05:00 KST) + 고아 파일만 매시 15분.
 *
 * <p>지우는 것: ① 오탐 비공개 후 유예(24시간)가 지난 클립 ② 보관 기간(최대 30일)이 지난 클립 ③ 카메라가 사라진 클립
 * (삭제 이벤트와 요청 중이던 클립이 엇갈린 경우) ④ 파일이 없는 행 ⑤ 행이 없는 파일·쓰다 남은 임시 파일(스윕 purge 경로,
 * 리스너 실패 포함). ④⑤는 만든 지 1시간이 지난 것만 본다 - 저장 중(파일 → 행 사이)인 클립을 고아로 오인하지 않게 한다.</p>
 *
 * <p>⑤ 고아 파일은 <b>매시 15분</b>에 따로 돈다(점검 L-3) - 탈퇴 리스너가 실패해 스윕 purge가 계정을 지우면 행은 CASCADE로
 * 사라지지만 파일은 남는다. 하루 1회면 탈퇴자 영상이 최대 하루 디스크에 남아 "탈퇴자 데이터를 붙들지 않는다"에 어긋난다.
 * 디렉터리 목록 + 파일 이름 조회뿐이라 가볍다.</p>
 *
 * <p>05:00인 이유 - 토큰 03:00·FCM 토큰 04:00·알림 이력 04:30과 겹치지 않게 한다(스케줄러 풀 3스레드).
 * <b>킬 스위치({@code anomaly.clip.enabled})와 무관하게 돈다</b> - 생성을 멈춰도 만료·삭제는 계속돼야 한다.</p>
 *
 * <p>행을 먼저 지우고 파일을 지운다. 파일 삭제가 실패하면 다음 날 ⑤가 회수한다. 단계마다 예외를 삼켜 한 단계의 실패가
 * 나머지를 막지 않게 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyClipCleanupScheduler {

    private static final int BATCH = 500;
    /** 한 번에 처리할 최대 묶음 수 - 무한 반복 방지(남은 건 다음 날). */
    private static final int MAX_BATCHES = 100;
    private static final Duration SETTLE = Duration.ofHours(1);

    private final AnomalyClipRepository clipRepository;
    private final AnomalyClipStorage storage;
    private final CameraService cameraService;
    private final AnomalyProperties properties;

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        OffsetDateTime now = OffsetDateTime.now();
        run("hidden", () -> purgeHidden(now));
        run("retention", () -> purgeExpired(now));
        run("camera-gone", this::purgeCameraGone);
        run("missing-file", () -> purgeMissingFiles(now));
    }

    /** 행이 없는 파일·임시 파일 회수 - 매시 15분(점검 L-3). 킬 스위치와 무관하게 돈다. */
    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Seoul")
    public void cleanupOrphanFiles() {
        run("orphan-file", () -> purgeOrphanFiles(Instant.now()));
    }

    /** 오탐 비공개 후 유예가 지난 클립. 판정이 번복돼 다시 공개된 클립은 조건부 삭제가 건너뛴다. */
    int purgeHidden(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusHours(properties.getClip().getHiddenGraceHours());
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<AnomalyClip> clips = clipRepository.findByStatusAndHiddenAtBefore(
                    AnomalyClipStatus.HIDDEN, cutoff, PageRequest.of(0, BATCH));
            if (clips.isEmpty()) {
                break;
            }
            int before = deleted;
            for (AnomalyClip clip : clips) {
                if (clipRepository.deleteHiddenById(clip.getId(), cutoff) == 1) {
                    storage.delete(clip.getFileName());
                    deleted++;
                }
            }
            if (deleted == before) {
                break;   // 이번 묶음을 하나도 못 지웠다(동시 번복 등) - 같은 묶음을 계속 읽지 않는다
            }
        }
        return deleted;
    }

    /** 보관 기간이 지난 클립(상태 무관). */
    int purgeExpired(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(properties.getClip().effectiveRetentionDays());
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<AnomalyClip> clips = clipRepository.findByCreatedAtBefore(cutoff, PageRequest.of(0, BATCH));
            if (clips.isEmpty()) {
                break;
            }
            deleted += deleteRowsThenFiles(clips);
        }
        return deleted;
    }

    /** 카메라가 사라진 세션의 클립 - 삭제 이벤트 처리와 요청 중이던 클립 기록이 엇갈렸거나 리스너가 실패한 경우. */
    int purgeCameraGone() {
        List<String> sessionIds = clipRepository.findDistinctSessionIds();
        if (sessionIds.isEmpty()) {
            return 0;
        }
        Map<String, String> alive = cameraService.findLabelsBySessionIds(sessionIds);
        List<String> gone = sessionIds.stream().filter(sessionId -> !alive.containsKey(sessionId)).toList();
        if (gone.isEmpty()) {
            return 0;
        }
        return deleteRowsThenFiles(clipRepository.findBySessionIdIn(gone));
    }

    /** 파일이 사라진 행. */
    int purgeMissingFiles(OffsetDateTime now) {
        int deleted = 0;
        for (AnomalyClip clip : clipRepository.findByCreatedAtBeforeOrderByIdAsc(now.minus(SETTLE))) {
            if (storage.find(clip.getFileName()).isEmpty()
                    && clipRepository.deleteOneById(clip.getId()) == 1) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 행이 없는 파일·쓰다 남은 임시 파일. */
    int purgeOrphanFiles(Instant now) {
        Instant settledBefore = now.minus(SETTLE);
        Set<String> known = new HashSet<>(clipRepository.findAllFileNames());
        int deleted = 0;
        for (StoredFile file : storage.listFiles()) {
            if (!file.modifiedAt().isBefore(settledBefore)) {
                continue;
            }
            if (file.temporary()) {
                storage.deleteTemporary(file.name());
                deleted++;
            } else if (!known.contains(file.name())) {
                storage.delete(file.name());
                deleted++;
            }
        }
        return deleted;
    }

    private int deleteRowsThenFiles(List<AnomalyClip> clips) {
        int deleted = 0;
        for (AnomalyClip clip : clips) {
            if (clipRepository.deleteOneById(clip.getId()) == 1) {
                storage.delete(clip.getFileName());
                deleted++;
            }
        }
        return deleted;
    }

    private void run(String step, IntSupplier task) {
        try {
            int count = task.getAsInt();
            if (count > 0) {
                log.info("[ANOMALY-CLIP] 청소: step={}, count={}", step, count);
            }
        } catch (RuntimeException e) {
            log.error("[ANOMALY-CLIP] 청소 실패 - 다음 주기에 재시도: step={}, error={}", step, e.getClass().getSimpleName());
        }
    }
}
