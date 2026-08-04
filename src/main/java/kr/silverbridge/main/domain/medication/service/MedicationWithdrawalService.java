package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.medication.entity.Medication;
import kr.silverbridge.main.domain.medication.repository.MedicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 보호자 탈퇴 시 그 보호자가 등록한 약을 정리한다.
 *
 * <p><b>왜 여기서 명시적으로 지우는가</b>: {@code medication.created_by}는 FK가 {@code ON DELETE CASCADE}라
 * 회원 행이 사라지면 약도 자동으로 지워진다. 하지만 그렇게 되면 <b>몇 건이 중지됐는지 셀 수 없어</b> 남은
 * 보호자에게 안내를 보낼 수 없다. 그래서 삭제 전에 피보호자별 건수를 집계해 돌려주고, DB CASCADE는 이
 * 경로가 실패했거나 스윕 purge로 리스너를 건너뛴 경우를 회수하는 안전망으로만 남긴다.</p>
 *
 * <p>피보호자가 탈퇴한 경우에는 이 경로에 걸리는 약이 없다 — 약을 등록하는 주체는 보호자뿐이라
 * {@code created_by}가 피보호자인 행은 존재하지 않는다. 피보호자 본인의 약은 {@code ward_id} CASCADE로 정리된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationWithdrawalService {

    private final MedicationRepository medicationRepository;

    /**
     * 탈퇴하는 보호자가 등록한 약을 모두 삭제하고, 피보호자별로 <b>중지된 약 건수</b>를 돌려준다.
     *
     * <p>반환 건수에는 이미 삭제(soft delete)된 약은 포함하지 않는다 — 그건 이번 탈퇴로 중지된 게 아니라
     * 이전에 이미 없어진 약이라 안내 문구의 숫자에 들어가면 사실과 다르다. 다만 <b>삭제 대상</b>에는 포함해
     * 등록자가 사라진 잔여 행을 남기지 않는다.</p>
     *
     * @return 피보호자 ID → 중지된 약 건수. 정리할 약이 없으면 빈 맵
     */
    @Transactional
    public Map<String, Integer> removeMedicationsRegisteredBy(String guardianId) {
        List<Medication> medications = medicationRepository.findByCreatedBy(guardianId);
        if (medications.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> stoppedCountByWard = medications.stream()
                .filter(medication -> !medication.isDeleted())
                .collect(Collectors.groupingBy(
                        Medication::getWardId, LinkedHashMap::new, Collectors.summingInt(medication -> 1)));

        // medication_intake는 FK ON DELETE CASCADE로 함께 삭제된다.
        medicationRepository.deleteAll(medications);
        log.info("[WITHDRAW] 복약 정리: guardianId={}, 삭제={}건, 영향 피보호자={}명",
                guardianId, medications.size(), stoppedCountByWard.size());

        return stoppedCountByWard;
    }
}
