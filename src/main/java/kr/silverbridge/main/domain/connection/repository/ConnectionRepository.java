package kr.silverbridge.main.domain.connection.repository;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    // 특정 보호자의 연결 목록 조회
    Page<Connection> findByGuardianId(String guardianId, Pageable pageable);

    // 동일한 guardian-ward 쌍의 활성 연결 존재 여부 확인
    boolean existsByGuardianIdAndWardIdAndStatusNot(String guardianId, String wardId, ConnectionStatus status);

    // 역할 변경 시 해당 사용자(보호자 또는 피보호자)의 ACTIVE/PENDING 연결 일괄 조회
    @Query("SELECT c FROM Connection c WHERE (c.guardianId = :userId OR c.wardId = :userId) AND c.status IN :statuses")
    List<Connection> findActiveByUserId(@Param("userId") String userId, @Param("statuses") List<ConnectionStatus> statuses);
}
