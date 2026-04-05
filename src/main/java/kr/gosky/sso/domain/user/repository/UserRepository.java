package kr.gosky.sso.domain.user.repository;

import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.global.enums.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    // 이메일로 사용자 조회 (로그인, 중복 검사)
    Optional<User> findByEmail(String email);

    // 이메일 존재 여부 (회원가입 중복 검사)
    boolean existsByEmail(String email);

    // 소셜 로그인 사용자 조회 (provider + providerId)
    Optional<User> findByProviderAndProviderId(Provider provider, String providerId);
}
