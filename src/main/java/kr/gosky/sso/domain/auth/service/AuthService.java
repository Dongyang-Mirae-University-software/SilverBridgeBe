package kr.gosky.sso.domain.auth.service;

import kr.gosky.sso.domain.auth.dto.RegisterRequest;
import kr.gosky.sso.domain.auth.entity.AccessLog;
import kr.gosky.sso.domain.auth.repository.AccessLogRepository;
import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.enums.Provider;
import kr.gosky.sso.global.enums.Role;
import kr.gosky.sso.global.enums.Status;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import kr.gosky.sso.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AccessLogRepository accessLogRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    // 회원가입
    // 이메일 중복 확인 → 비밀번호 암호화 → UUID로 사용자 생성
    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phone(request.getPhone())
                .role(Role.USER)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .emailVerified(false)
                .build();

        userRepository.save(user);
    }

    // 접속 로그 저장 공통 메서드
    protected void saveAccessLog(String userId, String action, String ipAddress, String userAgent) {
        accessLogRepository.save(AccessLog.builder()
                .userId(userId)
                .action(action)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build());
    }
}
