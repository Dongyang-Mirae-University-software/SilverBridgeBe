package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CheckinSettingRepository extends JpaRepository<CheckinSetting, Long> {

    Optional<CheckinSetting> findByWardId(String wardId);

    /** 안부 알림을 켠 피보호자(스케줄러). 켠 사람만 행이 의미를 가진다. */
    List<CheckinSetting> findByPromptEnabledTrue();

    /** 그 보호자가 켠 안부 알림만 끈다(다른 보호자·본인이 켠 것은 그대로). 반환: 바뀐 행 수. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE checkin_setting SET prompt_enabled = FALSE, prompt_changed_by = NULL, updated_at = now()
            WHERE ward_id = :wardId AND prompt_enabled AND prompt_changed_by = :guardianId
            """, nativeQuery = true)
    int disablePromptEnabledBy(@Param("wardId") String wardId, @Param("guardianId") String guardianId);
}
