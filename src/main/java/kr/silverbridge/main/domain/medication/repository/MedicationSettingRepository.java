package kr.silverbridge.main.domain.medication.repository;

import kr.silverbridge.main.domain.medication.entity.MedicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 설정 행이 없을 때만 기본값으로 만든다. 이미 있으면(동시 요청이 먼저 만들었으면) 아무것도 하지 않는다.
     *
     * <p><b>왜 find 후 save가 아닌가</b>(MED-G13): 행이 없는 피보호자에 대해 두 보호자가 동시에 토글하면 둘 다
     * "없음"을 읽고 INSERT해 늦은 쪽이 UNIQUE 위반(409 "중복된 값")으로 실패했다. 그 예외를 잡아 재조회하는 방식은
     * 이미 rollback-only가 된 트랜잭션이라 쓸 수 없다. {@code ON CONFLICT DO NOTHING}은 먼저 들어간 쪽의 커밋을
     * 기다렸다가 조용히 넘어가므로, 호출자는 이어서 다시 조회하면 된다.</p>
     *
     * @return 실제로 만든 행 수(0 또는 1)
     */
    @Modifying
    @Query(value = """
            INSERT INTO medication_setting (user_id, alarm_enabled, remind_again_enabled)
            VALUES (:userId, :alarmEnabled, :remindAgainEnabled)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("alarmEnabled") boolean alarmEnabled,
                       @Param("remindAgainEnabled") boolean remindAgainEnabled);
}
