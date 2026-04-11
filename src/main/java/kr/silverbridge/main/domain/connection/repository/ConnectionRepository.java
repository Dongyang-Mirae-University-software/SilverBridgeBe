package kr.silverbridge.main.domain.connection.repository;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    // 특정 보호자의 연결 목록 조회
    Page<Connection> findByGuardianId(String guardianId, Pageable pageable);

    // 동일한 guardian-ward 쌍의 활성 연결 존재 여부 확인
    boolean existsByGuardianIdAndWardIdAndStatusNot(String guardianId, String wardId, ConnectionStatus status);
}
