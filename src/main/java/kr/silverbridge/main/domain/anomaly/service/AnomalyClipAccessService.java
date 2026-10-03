package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalyClipItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyIncident;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyIncidentRepository;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 이상감지 클립 열람 인가(2026-10-04).
 *
 * <ul>
 *   <li><b>보호자</b> - 요청 시점에 ACTIVE 연결인 피보호자의 클립만. 인가는 {@code isActiveConnection}만 쓴다
 *       ({@code getMyWards}는 PENDING이 섞인다). 위반 403 {@code ANOMALY_NOT_AUTHORIZED} + {@code [IDOR-ATTEMPT]}.</li>
 *   <li><b>피보호자 본인</b> - 본인 집 클립이되 <b>ACTIVE 연결이 1건 이상일 때만</b>. 연결이 모두 끊기면 404로 비공개되고
 *       재연결하면 다시 보인다(연결 해제로 파일을 지우지 않는다). 남의 클립은 403 {@code ANOMALY_CLIP_NOT_OWNED} +
 *       {@code [IDOR-ATTEMPT]}.</li>
 *   <li><b>관리자</b> - 열람하지 않는다. 이 서비스에 관리자 경로를 만들지 말 것.</li>
 * </ul>
 *
 * <p>비공개(오탐 확정)·보관 기간 경과·파일 없음은 모두 404다. 응답에는 소유자·경로·사유를 싣지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyClipAccessService {

    private final AnomalyClipService clipService;
    private final AnomalyClipStorage storage;
    private final AnomalyIncidentRepository incidentRepository;
    private final ConnectionService connectionService;

    /** 재생할 파일. */
    public record ClipFile(Long clipId, Path path) {}

    public List<AnomalyClipItem> guardianClips(String guardianId, Long incidentId) {
        AnomalyIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_INCIDENT_NOT_FOUND));
        requireGuardianConnected(guardianId, incident.getWardId(), "incidentId", incidentId);
        return toItems(clipService.visibleClipsOf(incidentId));
    }

    public ClipFile guardianFile(String guardianId, Long clipId) {
        AnomalyClip clip = clipService.findViewable(clipId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_CLIP_NOT_FOUND));
        requireGuardianConnected(guardianId, clip.getWardId(), "clipId", clipId);
        return fileOf(clip);
    }

    public List<AnomalyClipItem> wardClips(String wardId, Long incidentId) {
        AnomalyIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_INCIDENT_NOT_FOUND));
        requireOwnWithConnection(wardId, incident.getWardId(), "incidentId", incidentId);
        return toItems(clipService.visibleClipsOf(incidentId));
    }

    public ClipFile wardFile(String wardId, Long clipId) {
        AnomalyClip clip = clipService.findViewable(clipId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANOMALY_CLIP_NOT_FOUND));
        requireOwnWithConnection(wardId, clip.getWardId(), "clipId", clipId);
        return fileOf(clip);
    }

    private void requireGuardianConnected(String guardianId, String wardId, String target, Long targetId) {
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 이상감지 영상 접근 시도: guardianId={}, {}={}",
                    guardianId, target, targetId);
            throw new CustomException(ErrorCode.ANOMALY_NOT_AUTHORIZED);
        }
    }

    private void requireOwnWithConnection(String wardId, String ownerWardId, String target, Long targetId) {
        if (!wardId.equals(ownerWardId)) {
            log.warn("[IDOR-ATTEMPT] 다른 피보호자 이상감지 영상 접근 시도: wardId={}, {}={}", wardId, target, targetId);
            throw new CustomException(ErrorCode.ANOMALY_CLIP_NOT_OWNED);
        }
        // 연결이 하나도 없으면 비공개 - 연결 해제로 파일을 지우지 않고 보이지만 않게 한다(재연결하면 다시 보인다)
        if (connectionService.getActiveGuardianIds(wardId).isEmpty()) {
            throw new CustomException(ErrorCode.ANOMALY_CLIP_NOT_FOUND);
        }
    }

    private ClipFile fileOf(AnomalyClip clip) {
        Path path = storage.find(clip.getFileName()).orElseThrow(() -> {
            log.warn("[ANOMALY-CLIP] 클립 행은 있으나 파일이 없음 - 청소가 회수 예정: clipId={}", clip.getId());
            return new CustomException(ErrorCode.ANOMALY_CLIP_NOT_FOUND);
        });
        return new ClipFile(clip.getId(), path);
    }

    private static List<AnomalyClipItem> toItems(List<AnomalyClip> clips) {
        return clips.stream().map(AnomalyClipItem::of).toList();
    }
}
