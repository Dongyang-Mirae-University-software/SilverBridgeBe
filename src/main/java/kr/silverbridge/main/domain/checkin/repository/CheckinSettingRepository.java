package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CheckinSettingRepository extends JpaRepository<CheckinSetting, Long> {

    Optional<CheckinSetting> findByWardId(String wardId);
}
