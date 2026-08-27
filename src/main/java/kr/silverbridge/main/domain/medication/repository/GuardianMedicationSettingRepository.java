package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalTime;
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

    /**
     * 시각을 직접 지정한 보호자 중 가장 이른 발송 시각. 지정한 보호자가 없으면 비어 있다.
     *
     * <p>스케줄러가 매 분 복약 테이블을 훑지 않도록, "아직 아무도 받을 시각이 아니다"를
     * 이 작은 테이블만으로 먼저 판정하기 위한 값이다({@code min}은 NULL을 무시한다).</p>
     */
    @Query("select min(s.missedAlertTime) from GuardianMedicationSetting s where s.missedAlertEnabled = true")
    Optional<LocalTime> findEarliestAlertTime();

    /** 시각을 직접 지정한 보호자 중 가장 늦은 발송 시각. 발송 창의 끝을 정하는 데 쓴다. */
    @Query("select max(s.missedAlertTime) from GuardianMedicationSetting s where s.missedAlertEnabled = true")
    Optional<LocalTime> findLatestAlertTime();
}
