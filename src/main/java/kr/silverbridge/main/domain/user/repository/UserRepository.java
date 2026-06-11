package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    // 이메일로 사용자 조회 (로그인, 중복 검사)
    Optional<User> findByEmail(String email);

    // 이메일 존재 여부 (회원가입 중복 검사)
    boolean existsByEmail(String email);

    // 전화번호 존재 여부 (회원가입·정보 수정 중복 검사)
    boolean existsByPhone(String phone);

    // 이름 + 전화번호로 사용자 전체 조회 (아이디 찾기 — LOCAL/KAKAO 복수 계정 지원)
    List<User> findAllByNameAndPhone(String name, String phone);

    // 전화번호로 사용자 조회 (SMS 비밀번호 재설정)
    Optional<User> findByPhone(String phone);

    // 소셜 로그인 사용자 조회 (provider + providerId)
    Optional<User> findByProviderAndProviderId(Provider provider, String providerId);

    // 소셜 로그인 사용자 존재 여부 (신규 가입 여부 판별)
    boolean existsByProviderAndProviderId(Provider provider, String providerId);

    // 탈퇴 진행 중 멈춘(좀비) 계정 조회 — INACTIVE는 탈퇴 흐름(withdraw)만 만들므로
    // 일정 시간 지난 INACTIVE 행은 purge 실패 잔여물이다 (WithdrawnUserPurgeScheduler, M-S1-1)
    List<User> findAllByStatusAndUpdatedAtBefore(Status status, OffsetDateTime cutoff);

    // 역할 목록으로 사용자 조회 (피보호자/보호자 필터링, 최신 가입순, 페이징)
    Page<User> findByRoleInOrderByCreatedAtDesc(List<Role> roles, Pageable pageable);

    // 최근 가입 회원 조회 (ADMIN 제외, Pageable 로 limit 지정, 가입 일시 내림차순)
    List<User> findByRoleNotOrderByCreatedAtDesc(Role role, Pageable pageable);

    // 관리자 회원관리 화면 — 키워드(name/phone LIKE) + role + status 통합 검색 (페이징)
    // 모든 필터는 null 허용 (null 이면 해당 조건 무시)
    // 이메일은 검색 대상에서 제외 (관리자는 이름/전화번호로 식별)
    // LIKE 메타문자(%, _)는 서비스 레이어에서 이스케이프 처리 → ESCAPE '\\' 명시
    @Query("""
            SELECT u FROM User u
            WHERE (:keyword IS NULL
                   OR u.name LIKE CONCAT('%', :keyword, '%') ESCAPE '\\'
                   OR u.phone LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
              AND (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> searchByKeywordAndFilters(
            @Param("keyword") String keyword,
            @Param("role") Role role,
            @Param("status") Status status,
            Pageable pageable);
}
