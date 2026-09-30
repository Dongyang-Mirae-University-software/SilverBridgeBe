package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자 역할 변경 시 복약 일정을 정리한다.
 *
 * <p><b>왜 필요한가</b>(2026-09-30 전체 점검 M-1): 피보호자가 보호자로 바뀌면 그 사람 앞으로 등록된 약이 살아 있는 채로
 * 남는다. 알림 스케줄러는 삭제·복용 시각·체크·알림 설정만 보므로 "약 드실 시간" 알림이 계속 가는데, 본인은 체크
 * (피보호자 전용)도 삭제(연결된 보호자 전용)도 할 수 없다. 카메라를 역할 변경과 함께 지우는 것과 같은 이유다.</p>
 *
 * <p><b>지우는 범위</b> - 그 사람이 <b>피보호자로서 갖고 있던</b> 약만이다. 보호자였던 사람이 남에게 등록해 준 약은
 * 건드리지 않는다: 일반 연결 해제 때도 그 약은 남고 다른 보호자가 관리한다(같은 기준). 복용 이력을 보존하려고
 * soft delete로 한다(보호자의 일반 삭제와 같은 방식).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationRoleChangeService {

    private final MedicationRepository medicationRepository;

    /**
     * 이 사용자가 피보호자로서 갖고 있던 살아 있는 약을 모두 중지한다. 호출자의 트랜잭션에 합류한다.
     *
     * @return 중지한 약 건수 (감사 로그 detail에 남긴다)
     */
    @Transactional
    public int stopAllOwnedByWard(String wardId) {
        List<Medication> medications = medicationRepository.findByWardIdAndDeletedAtIsNullOrderByDoseTimeAscIdAsc(wardId);
        if (medications.isEmpty()) {
            return 0;
        }
        OffsetDateTime now = MedicationClock.now();
        medications.forEach(medication -> medication.delete(now));
        log.info("[MEDICATION] 역할 변경으로 복약 일괄 중지: wardId={}, {}건", wardId, medications.size());
        return medications.size();
    }
}
