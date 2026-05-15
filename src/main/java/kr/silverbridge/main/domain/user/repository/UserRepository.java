package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
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

    // 역할 목록으로 사용자 조회 (피보호자/보호자 필터링, 최신 가입순)
    List<User> findByRoleInOrderByCreatedAtDesc(List<Role> roles);

    // 관리자 대시보드 — 가입자 통계 (현재 시점 vs baseline 시점) 단일 쿼리로 집계
    // baseline 은 비교 기준 시각 (예: 한 달 전 동일 시점)
    @Query(value = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'ACTIVE' AND role <> 'ADMIN')                            AS currentTotal,
                COUNT(*) FILTER (WHERE role <> 'ADMIN' AND created_at < :baseline)                       AS baselineTotal,
                COUNT(*) FILTER (WHERE status = 'ACTIVE' AND role = 'WARD')                              AS currentActiveWard,
                COUNT(*) FILTER (WHERE role = 'WARD' AND created_at < :baseline)                         AS baselineActiveWard
            FROM users
            """, nativeQuery = true)
    UserStatsProjection countUserStats(@Param("baseline") OffsetDateTime baseline);

    // 최근 가입 회원 조회 (ADMIN 제외, Pageable 로 limit 지정, 가입 일시 내림차순)
    List<User> findByRoleNotOrderByCreatedAtDesc(Role role, Pageable pageable);

    interface UserStatsProjection {
        long getCurrentTotal();
        long getBaselineTotal();
        long getCurrentActiveWard();
        long getBaselineActiveWard();
    }
}
