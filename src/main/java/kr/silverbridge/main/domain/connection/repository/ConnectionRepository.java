package kr.silverbridge.main.domain.connection.repository;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {

    // 보호자 기준 전체 연결 목록 조회 (요청 이력, 최신 요청순)
    List<Connection> findByGuardianIdOrderByCreatedAtDesc(String guardianId);

    // 보호자 기준 복수 상태 연결 목록 조회 (ACTIVE + PENDING, 최신 요청순)
    List<Connection> findByGuardianIdAndStatusInOrderByCreatedAtDesc(
            String guardianId, List<ConnectionStatus> statuses);

    // 피보호자의 상태별 보호자 목록 (연결 생성 오래된 순 / ACTIVE 보호자 목록)
    List<Connection> findByWardIdAndStatusOrderByCreatedAtAsc(String wardId, ConnectionStatus status);

    // 피보호자의 상태별 보호자 목록 (요청일 최신순 / PENDING "요청온 목록")
    List<Connection> findByWardIdAndStatusOrderByCreatedAtDesc(String wardId, ConnectionStatus status);

    // 동일한 guardian-ward 쌍의 활성/대기 연결 존재 여부 확인 (취소 제외)
    boolean existsByGuardianIdAndWardIdAndStatusNot(String guardianId, String wardId, ConnectionStatus status);
}
