package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 보호자별 복약 알림 수신 설정 조회. 행이 없으면 미설정(기본값 ON)이다.
 */
public interface GuardianMedicationSettingRepository extends JpaRepository<GuardianMedicationSetting, Long> {

    Optional<GuardianMedicationSetting> findByGuardianId(String guardianId);

    /** 발송 대상 보호자들의 설정을 한 번에 조회(보호자별 개별 조회로 인한 N+1 회피). */
    List<GuardianMedicationSetting> findByGuardianIdIn(Collection<String> guardianIds);
}
