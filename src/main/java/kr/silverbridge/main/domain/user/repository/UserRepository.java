package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
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

    // (관리자 회원관리 화면용 검색·역할 필터 쿼리 3종은 호출처 없는 dead code라 제거 — L-S3-5.
    //  회원관리 기능 구현 시 git 이력(2026-06-11 이전)에서 복원할 것.)

    // ===== 관리자 대시보드 집계 =====

    /** 회원 수. ADMIN은 서비스 이용자가 아니라 운영자라 지표에서 제외한다. */
    long countByStatusAndRoleNot(Status status, Role role);

    /** 역할별 회원 수(회원 구성). */
    long countByStatusAndRole(Status status, Role role);

    /** 오늘 신규 가입(KST 기준 시각은 호출부가 계산해 넘긴다 — 서버 타임존에 의존하지 않기 위함). */
    long countByStatusAndRoleNotAndCreatedAtGreaterThanEqual(Status status, Role role, OffsetDateTime from);

    /**
     * 가입 추이용 원본 시각. 날짜별 집계를 <b>DB가 아니라 애플리케이션에서</b> 한다.
     *
     * <p>날짜 그룹핑을 SQL로 하면 타임존 변환이 DB 방언에 묶여 "오늘"의 경계가 배포 환경마다 달라진다.
     * 최근 7일 가입은 건수가 적어 원본을 받아 KST로 묶는 편이 안전하고 검증도 쉽다.</p>
     */
    @Query("select u.createdAt from User u "
            + "where u.status = :status and u.role <> :excludedRole and u.createdAt >= :from")
    List<OffsetDateTime> findCreatedAtSince(@Param("status") Status status,
                                            @Param("excludedRole") Role excludedRole,
                                            @Param("from") OffsetDateTime from);

    /**
     * ACTIVE 연결이 하나도 없는 피보호자 수 - "보호 사각지대"의 핵심 지표.
     *
     * <p>PENDING은 연결로 치지 않는다. 요청만 와 있고 수락되지 않은 상태는 <b>아무도 지켜보지 않는
     * 상태</b>이며, 이것을 연결로 세면 사각지대가 실제보다 적게 보인다.</p>
     */
    @Query("select count(u) from User u "
            + "where u.role = :ward and u.status = :active "
            + "and not exists (select 1 from Connection c "
            + "                where c.wardId = u.id and c.status = :connected)")
    long countWardsWithoutActiveGuardian(@Param("ward") Role ward,
                                         @Param("active") Status active,
                                         @Param("connected") ConnectionStatus connected);

    /** 카메라를 한 대도 등록하지 않은 피보호자 수. is_active(사용자 토글)와 무관하게 "등록 자체가 없는" 경우만 센다. */
    @Query("select count(u) from User u "
            + "where u.role = :ward and u.status = :active "
            + "and not exists (select 1 from Camera cam where cam.wardId = u.id)")
    long countWardsWithoutCamera(@Param("ward") Role ward, @Param("active") Status active);
}
