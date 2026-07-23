package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SosSettingRepository extends JpaRepository<SosSetting, Long> {

    // 사용자당 한 행 — 조회(기본값 병합)와 upsert 시 기존 행 확인에 함께 쓴다.
    Optional<SosSetting> findByUserId(String userId);
}
