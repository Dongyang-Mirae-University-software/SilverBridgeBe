package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.client.AiClipClient.ClipMeta;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyClipRepository;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.camera.service.CameraService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 이상감지 클립 메타(DB) 작업 - 기록·공개 상태·이력 요약·탈퇴/카메라 삭제 정리.
 *
 * <p>파일 바이트는 다루지 않는다(그건 {@link AnomalyClipStorage}). 행을 지우는 메서드는 지운 파일 이름을 돌려주고,
 * 파일은 <b>행 삭제가 커밋된 뒤</b> 호출부가 지운다 - 반대 순서면 롤백 시 행만 남고 파일이 없는 클립이 생긴다
 * (남더라도 청소가 회수하지만, 열람 중 404가 나지 않게 하는 쪽을 택했다).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyClipService {

    private final AnomalyClipRepository clipRepository;
    private final AnomalyIncidentRepository incidentRepository;
    private final CameraService cameraService;
    private final AnomalyProperties properties;

    /** 이력 목록의 대표 클립(최신 공개 1건)과 공개 클립 수. */
    public record ClipSummary(AnomalyClip latest, int count) {}

    /** 저장을 마친 파일을 행으로 남기기 위한 값. */
    public record StoredClip(Long incidentId, String wardId, String sessionId, String fileName, long sizeBytes,
                             ClipMeta meta, OffsetDateTime detectedAt) {}

    /**
     * 저장된 파일을 클립 행으로 기록한다. 기록하지 않았으면(상황·카메라가 사라짐, 상한 도달) 빈 값 - 호출부가 파일을 지운다.
     *
     * <p><b>상황 행 쓰기 잠금 안에서</b> 판정 상태를 읽는다. 보호자 응답과 한 줄로 서게 해, "오탐 확정 직후 도착한 클립이
     * 공개 상태로 남는" 경합을 막는다(오탐 비공개도 같은 잠금 안에서 일어난다). 이미 오탐인 상황의 클립은 비공개로 태어난다.</p>
     */
    @Transactional
    public Optional<AnomalyClip> record(StoredClip stored) {
        Optional<AnomalyIncident> locked = incidentRepository.findByIdForUpdate(stored.incidentId());
        if (locked.isEmpty() || !locked.get().getWardId().equals(stored.wardId())) {
            log.info("[ANOMALY-CLIP] 상황이 사라져 기록 안 함: incidentId={}", stored.incidentId());
            return Optional.empty();
        }
        // 요청 사이에 카메라가 삭제(또는 다른 회원 소유로 바뀜)됐으면 남기지 않는다 - 삭제 정리가 이미 지나갔을 수 있다
        boolean cameraAlive = cameraService.findOwnerBySessionId(stored.sessionId())
                .filter(owner -> owner.wardId().equals(stored.wardId()))
                .isPresent();
        if (!cameraAlive) {
            log.info("[ANOMALY-CLIP] 카메라가 삭제돼 기록 안 함: sessionId={}", stored.sessionId());
            return Optional.empty();
        }
        if (clipRepository.countByIncidentId(stored.incidentId()) >= properties.getClip().getMaxPerIncident()) {
            log.info("[ANOMALY-CLIP] 상황당 클립 상한 도달 - 기록 안 함: incidentId={}", stored.incidentId());
            return Optional.empty();
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean hidden = locked.get().getReviewStatus() == AnomalyReviewStatus.FALSE_ALARM;
        ClipMeta meta = stored.meta();
        AnomalyClip clip = clipRepository.save(AnomalyClip.builder()
                .incidentId(stored.incidentId())
                .wardId(stored.wardId())
                .sessionId(stored.sessionId())
                .fileName(stored.fileName())
                .sizeBytes(stored.sizeBytes())
                .durationMs(meta == null ? null : meta.durationMs())
                .frameCount(meta == null ? null : meta.frames())
                .width(meta == null ? null : meta.width())
                .height(meta == null ? null : meta.height())
                .clipStartedAt(meta == null ? null : meta.startedAt())
                .detectedAt(stored.detectedAt())
                .status(hidden ? AnomalyClipStatus.HIDDEN : AnomalyClipStatus.VISIBLE)
                .hiddenAt(hidden ? now : null)
                .build());
        return Optional.of(clip);
    }

    /**
     * 판정 결과에 맞춰 공개 상태를 바꾼다 - <b>보호자 응답 트랜잭션 안</b>(상황 행 쓰기 잠금 안)에서만 부른다.
     *
     * <p>오탐 확정이면 즉시 비공개(24시간 뒤 청소가 물리 삭제), 그 밖(REAL·CONFLICTED·PENDING)이면 비공개였던 클립을
     * 되살린다. 동수·판정 대기는 지우지도 숨기지도 않는다.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyReviewStatus(Long incidentId, AnomalyReviewStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        if (status == AnomalyReviewStatus.FALSE_ALARM) {
            int hidden = clipRepository.hideByIncidentId(incidentId, now);
            if (hidden > 0) {
                log.info("[ANOMALY-CLIP] 오탐 확정 - 클립 비공개: incidentId={}, count={}", incidentId, hidden);
            }
        } else {
            int restored = clipRepository.restoreByIncidentId(incidentId, now);
            if (restored > 0) {
                log.info("[ANOMALY-CLIP] 판정 번복 - 클립 복구: incidentId={}, count={}, reviewStatus={}",
                        incidentId, restored, status);
            }
        }
    }

    /** 이력 목록용 - 상황별 대표 클립(최신 공개 1건)과 공개 클립 수. 공개 클립이 없는 상황은 항목이 없다. */
    @Transactional(readOnly = true)
    public Map<Long, ClipSummary> summarize(Collection<Long> incidentIds) {
        Map<Long, ClipSummary> summaries = new HashMap<>();
        if (incidentIds.isEmpty()) {
            return summaries;
        }
        OffsetDateTime expiredBefore = retentionCutoff();
        // 최신순이라 상황별 첫 행이 대표 클립이다
        for (AnomalyClip clip : clipRepository.findByIncidentIdInAndStatusOrderByCreatedAtDesc(
                incidentIds, AnomalyClipStatus.VISIBLE)) {
            if (clip.getCreatedAt().isBefore(expiredBefore)) {
                continue;
            }
            summaries.merge(clip.getIncidentId(), new ClipSummary(clip, 1),
                    (existing, ignored) -> new ClipSummary(existing.latest(), existing.count() + 1));
        }
        return summaries;
    }

    /** 상황의 공개 클립(최신순, 보관 기간 안). 인가는 호출부 책임이다. */
    @Transactional(readOnly = true)
    public List<AnomalyClip> visibleClipsOf(Long incidentId) {
        OffsetDateTime expiredBefore = retentionCutoff();
        return clipRepository.findByIncidentIdAndStatusOrderByCreatedAtDesc(incidentId, AnomalyClipStatus.VISIBLE)
                .stream()
                .filter(clip -> !clip.getCreatedAt().isBefore(expiredBefore))
                .toList();
    }

    /** 열람 가능한 클립 1건(공개 + 보관 기간 안). 인가는 호출부 책임이다. */
    @Transactional(readOnly = true)
    public Optional<AnomalyClip> findViewable(Long clipId) {
        OffsetDateTime expiredBefore = retentionCutoff();
        return clipRepository.findById(clipId)
                .filter(AnomalyClip::isVisible)
                .filter(clip -> !clip.getCreatedAt().isBefore(expiredBefore));
    }

    /**
     * 피보호자 탈퇴 - 그 피보호자의 클립 행을 지우고 파일 이름을 돌려준다.
     *
     * <p>탈퇴 리스너는 <b>동기 AFTER_COMMIT</b>이라 {@code REQUIRES_NEW}여야 이 삭제가 실제로 커밋된다(H-1, 2026-09-30).</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> deleteAllOfWard(String wardId) {
        return deleteRows(clipRepository.findByWardId(wardId));
    }

    /** 카메라 삭제 - 그 카메라(세션)의 클립 행을 지우고 파일 이름을 돌려준다. 동기 AFTER_COMMIT 리스너용이라 {@code REQUIRES_NEW}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> deleteAllOfCameras(String wardId, Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return deleteRows(clipRepository.findByWardIdAndSessionIdIn(wardId, sessionIds));
    }

    OffsetDateTime retentionCutoff() {
        return OffsetDateTime.now().minusDays(properties.getClip().effectiveRetentionDays());
    }

    private List<String> deleteRows(List<AnomalyClip> clips) {
        if (clips.isEmpty()) {
            return List.of();
        }
        clipRepository.deleteAllByIdIn(clips.stream().map(AnomalyClip::getId).toList());
        return clips.stream().map(AnomalyClip::getFileName).toList();
    }
}
