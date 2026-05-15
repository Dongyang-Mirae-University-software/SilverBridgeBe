package kr.silverbridge.main.domain.admin.repository;

import kr.silverbridge.main.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

/**
 * 관리자 화면 전용 사용자 집계 쿼리 모음 (대시보드/회원관리)
 * - 일반 user 도메인 조회와 책임을 분리하기 위해 admin 패키지에 둔다.
 * - User 엔티티에 대한 다중 리포지토리이며, 단일 엔티티 1리포지토리 컨벤션의 예외이다.
 */
public interface AdminUserStatsRepository extends JpaRepository<User, String> {

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

    // 관리자 회원관리 탭별 건수 (전체/WARD/GUARDIAN/ADMIN) 단일 쿼리 집계
    @Query(value = """
            SELECT
                COUNT(*)                                     AS total,
                COUNT(*) FILTER (WHERE role = 'WARD')        AS ward,
                COUNT(*) FILTER (WHERE role = 'GUARDIAN')    AS guardian,
                COUNT(*) FILTER (WHERE role = 'ADMIN')       AS admin
            FROM users
            """, nativeQuery = true)
    UserRoleCountProjection countByRole();

    interface UserStatsProjection {
        long getCurrentTotal();
        long getBaselineTotal();
        long getCurrentActiveWard();
        long getBaselineActiveWard();
    }

    interface UserRoleCountProjection {
        long getTotal();
        long getWard();
        long getGuardian();
        long getAdmin();
    }
}
