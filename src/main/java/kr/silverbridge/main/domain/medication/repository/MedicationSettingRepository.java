package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 피보호자별 복약 알림 설정 조회. 행이 없으면 미설정(기본값 적용)이다.
 */
public interface MedicationSettingRepository extends JpaRepository<MedicationSetting, Long> {

    Optional<MedicationSetting> findByUserId(String userId);

    /** 보호자 목록 화면에서 피보호자 여러 명의 설정을 한 번에 조회. */
    List<MedicationSetting> findByUserIdIn(Collection<String> userIds);
}
