package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SosSettingRepository extends JpaRepository<SosSetting, Long> {

    // 사용자당 한 행 — 조회(기본값 병합)와 upsert 시 기존 행 확인에 함께 쓴다.
    Optional<SosSetting> findByUserId(String userId);

    /**
     * 행이 없을 때만 넣는다(SOS-G17). 반환: 넣은 건수(0 = 이미 있었음 - 동시 요청이 먼저 넣었다).
     *
     * <p>"조회 후 없으면 save"는 동시 최초 저장에서 둘 다 "없음"을 보고 INSERT해 {@code uq_sos_setting_user}
     * 위반(409)이 났다. 위반 예외를 잡아 재조회하는 방식은 예외가 난 트랜잭션이 rollback-only가 돼 쓸 수 없어,
     * DB가 충돌을 흡수하게 했다. 뒤에 온 쪽은 앞선 INSERT가 커밋될 때까지 기다린 뒤 0을 받는다.
     * 시각 컬럼은 감사(@CreatedDate)를 거치지 않으므로 직접 채운다.</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO sos_setting (user_id, sos_action, created_at, updated_at)
            VALUES (:userId, :sosAction, now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("sosAction") String sosAction);
}
