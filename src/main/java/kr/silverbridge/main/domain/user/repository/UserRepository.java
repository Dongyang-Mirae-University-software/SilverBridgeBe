package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
