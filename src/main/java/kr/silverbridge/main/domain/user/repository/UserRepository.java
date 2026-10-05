package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
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

    // ===== 관리자 회원관리 =====

    /**
     * 회원관리 목록/검색의 공통 WHERE 절. 목록 쿼리와 count 쿼리가 같은 조건을 써야 페이징이 어긋나지 않아
     * 상수로 뽑아 두 곳에서 이어 붙인다.
     *
     * <p>INACTIVE는 항상 제외한다 - 탈퇴 진행 중이거나 purge에 실패해 남은 좀비 행이라 관리자가 할 수 있는
     * 일이 없고, 스윕이 곧 회수한다. "이용 중/이용 제한" 두 상태만 회원 목록의 대상이다.</p>
     *
     * <p>연결 상태는 우선순위로 하나를 고른다 - ACTIVE가 하나라도 있으면 "연결됨"(1),
     * 없고 PENDING만 있으면 "수락 대기"(2), 둘 다 없으면 "미연결"(3). 관리자 계정은 연결 축이 없어
     * (표시값도 null) 연결 필터(CONNECTED·PENDING·NONE)를 걸면 <b>항상 제외</b>한다 - NONE("미연결")에 섞이면
     * "연결이 끊긴 회원"으로 읽힌다(ADMIN-G10). 필터를 걸지 않으면(ALL) 관리자도 목록에 나온다.</p>
     *
     * <p>키워드는 이름·이메일·전화번호에 더해 <b>연결된 상대의 이름</b>까지 훑는다. 상대 이름 검색은
     * 인덱스(V15)를 타지 못하는 서브쿼리지만, 회원 수 규모에서 문제되지 않는다.
     * 전화번호는 저장 형식(하이픈 유무)과 입력 형식이 달라도 찾히도록 양쪽의 하이픈을 제거해 비교한다
     * ({@code phoneKeyword}, 호출부가 하이픈을 뺀 값을 넘긴다. 빈 값이면 전화번호 조건을 건너뛴다 - ADMIN-G31).</p>
     *
     * <p>{@code concat} 안의 검색어는 {@code cast(... as String)}으로 타입을 못 박는다. 그대로 두면 값이 null일 때
     * Hibernate가 타입을 몰라 bytea로 바인딩하고, PostgreSQL이 LIKE 전체를 bytea로 풀다가 {@code escape '\'}에서
     * "invalid input syntax for type bytea"로 실패한다 - 검색어 없는 기본 목록이 500이 된다(2026-10-02 통합 테스트로 확인).</p>
     */
    String ADMIN_USER_SEARCH_WHERE =
            " u.status <> :excludedStatus "
            + " and (:role is null or u.role = :role) "
            + " and (:status is null or u.status = :status) "
            + " and (:keyword is null "
            + "      or lower(u.name) like concat('%', cast(:keyword as String), '%') escape '\\' "
            + "      or lower(u.email) like concat('%', cast(:keyword as String), '%') escape '\\' "
            + "      or (:phoneKeyword is not null "
            + "          and replace(u.phone, '-', '') like concat('%', cast(:phoneKeyword as String), '%') escape '\\') "
            + "      or exists (select 1 from Connection cg, User w "
            + "                 where cg.guardianId = u.id and w.id = cg.wardId "
            + "                   and cg.status in :linkedStatuses "
            + "                   and lower(w.name) like concat('%', cast(:keyword as String), '%') escape '\\') "
            + "      or exists (select 1 from Connection cw, User g "
            + "                 where cw.wardId = u.id and g.id = cw.guardianId "
            + "                   and cw.status in :linkedStatuses "
            + "                   and lower(g.name) like concat('%', cast(:keyword as String), '%') escape '\\')) "
            + " and (:connectionFilter is null or (u.role <> kr.silverbridge.main.global.enums.Role.ADMIN and :connectionFilter = "
            + "      case when exists (select 1 from Connection ca "
            + "                        where (ca.guardianId = u.id or ca.wardId = u.id) "
            + "                          and ca.status = :activeStatus) then 1 "
            + "           when exists (select 1 from Connection cp "
            + "                        where (cp.guardianId = u.id or cp.wardId = u.id) "
            + "                          and cp.status = :pendingStatus) then 2 "
            + "           else 3 end)) ";

    /** 회원관리 목록 - 키워드·역할·계정 상태·연결 상태 필터, 가입일 역순은 호출부 Pageable이 정한다. */
    @Query(value = "select u from User u where " + ADMIN_USER_SEARCH_WHERE,
           countQuery = "select count(u) from User u where " + ADMIN_USER_SEARCH_WHERE)
    Page<User> searchForAdmin(@Param("excludedStatus") Status excludedStatus,
                              @Param("role") Role role,
                              @Param("status") Status status,
                              @Param("keyword") String keyword,
                              @Param("phoneKeyword") String phoneKeyword,
                              @Param("connectionFilter") Integer connectionFilter,
                              @Param("linkedStatuses") List<ConnectionStatus> linkedStatuses,
                              @Param("activeStatus") ConnectionStatus activeStatus,
                              @Param("pendingStatus") ConnectionStatus pendingStatus,
                              Pageable pageable);

    /** 회원관리 탭별 건수. 목록과 같은 모집단이어야 하므로 여기서도 INACTIVE를 제외한다. */
    @Query("select u.role as role, count(u) as cnt from User u "
            + "where u.status <> :excludedStatus group by u.role")
    List<RoleCount> countByRoleExcludingStatus(@Param("excludedStatus") Status excludedStatus);

    /** 탭별 건수 집계 결과. 0건인 역할은 행 자체가 없으므로 호출부가 0으로 채운다. */
    interface RoleCount {
        Role getRole();
        long getCnt();
    }

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

    /**
     * 알림을 받을 수 있는 보호자가 한 명도 없는 피보호자 수(ADMIN-G27).
     *
     * <p>{@link #countWardsWithoutActiveGuardian}의 상위 집합이다 - ACTIVE 연결이 없는 피보호자에 더해,
     * 연결은 ACTIVE로 남아 있지만 그 보호자가 <b>전원 이용 중(ACTIVE)이 아닌</b> 피보호자를 센다.
     * 정지(RESTRICTED)는 연결을 정리하지 않아 위 지표에 잡히지 않는데, 정지 계정에는 SOS·화재 알림도
     * 나가지 않으므로(알림 차단은 수신자 기준) 실제로는 아무도 알림을 받지 못하는 사각지대다.</p>
     */
    @Query("select count(u) from User u "
            + "where u.role = :ward and u.status = :active "
            + "and not exists (select 1 from Connection c, User g "
            + "                where c.wardId = u.id and c.status = :connected "
            + "                and g.id = c.guardianId and g.status = :active)")
    long countWardsWithoutReachableGuardian(@Param("ward") Role ward,
                                            @Param("active") Status active,
                                            @Param("connected") ConnectionStatus connected);

    /**
     * 계정 상태만 PK로 조회한다(WebSocket 수신자 차단 판단용). 엔티티 전체를 읽지 않는다.
     * 행이 없으면 빈 값 - 호출부는 이를 "상태 모름"으로 다룬다.
     */
    @Query("select u.status from User u where u.id = :id")
    Optional<Status> findStatusById(@Param("id") String id);

    /** 카메라를 한 대도 등록하지 않은 피보호자 수. is_active(사용자 토글)와 무관하게 "등록 자체가 없는" 경우만 센다. */
    @Query("select count(u) from User u "
            + "where u.role = :ward and u.status = :active "
            + "and not exists (select 1 from Camera cam where cam.wardId = u.id)")
    long countWardsWithoutCamera(@Param("ward") Role ward, @Param("active") Status active);

    /**
     * 관리자 이상감지 로그 검색 - 이름 부분일치로 회원 ID만 모은다(피보호자 이름 검색용).
     * {@code keyword}는 호출부가 소문자화·LIKE 메타문자 이스케이프를 마친 값이다({@code escape '\'}와 짝).
     * 탈퇴해 행이 지워진 회원은 이름을 알 수 없어 검색되지 않는다.
     */
    @Query("select u.id from User u where lower(u.name) like concat('%', :keyword, '%') escape '\\'")
    List<String> findIdsByNameContaining(@Param("keyword") String keyword);

    // ===== 연결 생성 ↔ 역할 변경 직렬화 (CONN-G15, 2026-10-05) =====
    //
    // 연결 요청(행 INSERT)과 관리자 역할 변경(연결 정리)이 겹치면, 역할 변경 쪽 정리 조회가 아직 커밋되지 않은
    // 요청을 못 보고 지나가 "보호자가 된 사용자 앞으로 PENDING"이 남는다. INSERT는 기존 행이 아니라
    // 낙관적 잠금(@Version)으로도 못 막는다. 두 경로가 같은 users 행을 먼저 잠가 한 줄로 세운다.
    //
    // 잠금 종류를 고른 이유:
    //  - 연결 생성 쪽은 FOR SHARE - 연결 생성끼리는 서로 막지 않고, 역할·상태 UPDATE(FOR NO KEY UPDATE)와만 충돌한다.
    //  - 역할 변경 쪽은 FOR NO KEY UPDATE - 어차피 커밋 때 UPDATE가 잡는 잠금과 같은 종류를 "정리 조회 전에" 당겨 잡을 뿐이다.
    //    클립 기록의 FOR KEY SHARE(AnomalyClipRepository)와는 충돌하지 않는다(FOR UPDATE로 바꾸지 말 것 - 충돌하게 된다).
    //  - 순서는 언제나 users → connection. 탈퇴 purge(users → CASCADE)·클립(users → 상황)과 같은 순서라 교착이 없다.

    /**
     * 연결을 새로 만드는 경로(보호자 요청·관리자 강제 연결)가 <b>역할·상태를 읽기 전에</b> 부른다.
     * ID 순으로 잠가 여러 행을 잡는 순서를 고정한다. 없는 ID는 결과에서 빠질 뿐이다(호출부의 기존 404 처리).
     */
    @Query(value = "SELECT id FROM users WHERE id IN (:ids) ORDER BY id FOR SHARE", nativeQuery = true)
    List<String> lockForConnectionChange(@Param("ids") Collection<String> ids);

    /**
     * 관리자 회원 정보 수정(역할·상태)이 <b>연결 정리 전에</b> 부른다. 진행 중인 연결 생성이 커밋될 때까지 기다린 뒤
     * 정리 조회가 그 요청까지 보게 하고, 반대로 이 잠금이 먼저면 연결 생성 쪽이 바뀐 역할을 읽어 거절한다.
     */
    @Query(value = "SELECT id FROM users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<String> lockForAccountChange(@Param("id") String id);
}
