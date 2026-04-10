package kr.silverbridge.main.domain.user.repository;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    // 이메일로 사용자 조회 (로그인, 중복 검사)
    Optional<User> findByEmail(String email);

    // 이메일 존재 여부 (회원가입 중복 검사)
    boolean existsByEmail(String email);

    // 전화번호 존재 여부 (회원가입·정보 수정 중복 검사)
    boolean existsByPhone(String phone);

    // 이름 + 전화번호로 사용자 조회 (아이디 찾기)
    Optional<User> findByNameAndPhone(String name, String phone);

    // 전화번호로 사용자 조회 (SMS 비밀번호 재설정)
    Optional<User> findByPhone(String phone);

    // 소셜 로그인 사용자 조회 (provider + providerId)
    Optional<User> findByProviderAndProviderId(Provider provider, String providerId);

    // 소셜 로그인 사용자 존재 여부 (신규 가입 여부 판별)
    boolean existsByProviderAndProviderId(Provider provider, String providerId);

    // 역할 목록으로 사용자 조회 (피보호자/보호자 필터링)
    Page<User> findByRoleIn(List<Role> roles, Pageable pageable);
}
