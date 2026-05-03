package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.anomaly.repository.AnomalyEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 이상감지 이벤트 조회 전용 서비스.
 *
 * 이상감지 이벤트는 외부 AI 서버가 직접 적재하므로 본 서비스는 read-only 조회만 제공한다.
 * 관리자 화면이 호출하며, 도메인 외부에서는 본 서비스를 통해서만 anomaly 데이터에 접근한다.
 */
@Service
@RequiredArgsConstructor
public class AnomalyEventQueryService {

    private final AnomalyEventRepository anomalyEventRepository;

    // 기간 필터로 전체 이상감지 이벤트 조회 (최신 감지순)
    @Transactional(readOnly = true)
    public List<AnomalyEvent> findEvents(OffsetDateTime startDate, OffsetDateTime endDate) {
        return anomalyEventRepository.findByDateRange(startDate, endDate);
    }

    // 특정 보호자의 ACTIVE 연결 피보호자 이벤트만 조회 (최신 감지순)
    // 연결된 ward 가 없으면 빈 목록 반환
    @Transactional(readOnly = true)
    public List<AnomalyEvent> findEventsByGuardian(String guardianId,
                                                   OffsetDateTime startDate,
                                                   OffsetDateTime endDate) {
        List<String> wardIds = anomalyEventRepository.findActiveWardIdsByGuardianId(guardianId);
        if (wardIds.isEmpty()) {
            return Collections.emptyList();
        }
        return anomalyEventRepository.findByWardIdsAndDateRange(wardIds, startDate, endDate);
    }
}
