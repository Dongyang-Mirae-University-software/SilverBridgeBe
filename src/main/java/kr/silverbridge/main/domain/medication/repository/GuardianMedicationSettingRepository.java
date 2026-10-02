package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.GuardianMedicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 보호자가 특정 피보호자에 대해 받을 미복용 요약 설정 조회.
 * 행이 없으면 미설정(기본값 ON · 전역 기본 시각)이다.
 */
public interface GuardianMedicationSettingRepository extends JpaRepository<GuardianMedicationSetting, Long> {

    Optional<GuardianMedicationSetting> findByGuardianIdAndWardId(String guardianId, String wardId);

    /** 발송 판정 시, 한 피보호자의 보호자들 설정을 한 번에 조회(보호자별 개별 조회로 인한 N+1 회피). */
    List<GuardianMedicationSetting> findByWardIdAndGuardianIdIn(String wardId, Collection<String> guardianIds);

    /** 보호자 화면(피보호자 카드 목록)에서 내 설정을 한 번에 조회. */
    List<GuardianMedicationSetting> findByGuardianIdAndWardIdIn(String guardianId, Collection<String> wardIds);

    /**
     * 시각을 직접 지정한 설정 중 가장 이른 발송 시각. 지정한 설정이 없으면 비어 있다.
     *
     * <p>스케줄러가 매 분 복약 테이블을 훑지 않도록, "아직 아무도 받을 시각이 아니다"를
     * 이 작은 테이블만으로 먼저 판정하기 위한 값이다({@code min}은 NULL을 무시한다).</p>
     */
    @Query("select min(s.missedAlertTime) from GuardianMedicationSetting s where s.missedAlertEnabled = true")
    Optional<LocalTime> findEarliestAlertTime();

    /** 시각을 직접 지정한 설정 중 가장 늦은 발송 시각. 발송 창의 끝을 정하는 데 쓴다. */
    @Query("select max(s.missedAlertTime) from GuardianMedicationSetting s where s.missedAlertEnabled = true")
    Optional<LocalTime> findLatestAlertTime();

    /**
     * (보호자, 피보호자) 설정 행이 없을 때만 만든다. 이미 있으면 아무것도 하지 않는다(시각은 NULL = 미설정).
     *
     * <p>한 보호자가 두 탭에서 동시에 처음 저장하면 find 후 save는 늦은 쪽이 UNIQUE 위반(409)으로 실패한다(MED-G13).
     * 예외를 잡아 재조회하는 방식은 rollback-only 트랜잭션이라 쓸 수 없어 {@code ON CONFLICT DO NOTHING}으로 넘기고
     * 호출자가 다시 조회한다. {@link MedicationSettingRepository#insertIfAbsent}와 같은 방식이다.</p>
     *
     * @return 실제로 만든 행 수(0 또는 1)
     */
    @Modifying
    @Query(value = """
            INSERT INTO guardian_medication_setting (guardian_id, ward_id, missed_alert_enabled)
            VALUES (:guardianId, :wardId, :missedAlertEnabled)
            ON CONFLICT (guardian_id, ward_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("guardianId") String guardianId,
                       @Param("wardId") String wardId,
                       @Param("missedAlertEnabled") boolean missedAlertEnabled);
}
