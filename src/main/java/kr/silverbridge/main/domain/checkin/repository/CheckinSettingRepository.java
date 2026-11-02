package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckinSettingRepository extends JpaRepository<CheckinSetting, Long> {

    Optional<CheckinSetting> findByWardId(String wardId);

    /** 안부 알림을 켠 피보호자(스케줄러). 켠 사람만 행이 의미를 가진다. */
    List<CheckinSetting> findByPromptEnabledTrue();
}
