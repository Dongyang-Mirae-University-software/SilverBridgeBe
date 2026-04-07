package kr.gosky.sso.domain.auth.service;

import kr.gosky.sso.domain.auth.dto.PasswordResetConfirmRequest;
import kr.gosky.sso.domain.auth.dto.PasswordResetRequest;
import kr.gosky.sso.domain.auth.entity.AccessLog;
import kr.gosky.sso.domain.auth.repository.AccessLogRepository;
import kr.gosky.sso.domain.auth.repository.RefreshTokenRepository;
import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogRepository accessLogRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    private static final long RESET_TOKEN_TTL = 30L;     // 30분
    private static final String REDIS_PREFIX   = "password:reset:";

    // 비밀번호 재설정 요청
    // 이메일 확인 → UUID 토큰 생성 → Redis 저장(TTL 30분) → 이메일 발송
    @Transactional(readOnly = true)
    public void requestReset(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String token = UUID.randomUUID().toString();

        // Redis에 토큰 → userId 매핑 저장
        redisTemplate.opsForValue()
                .set(REDIS_PREFIX + token, user.getId(), RESET_TOKEN_TTL, TimeUnit.MINUTES);

        sendResetEmail(user.getEmail(), token);
        log.info("비밀번호 재설정 이메일 발송 완료: {}", user.getEmail());
    }

    // 비밀번호 재설정 확인
    // 토큰으로 userId 조회 → 비밀번호 변경 → Redis 삭제 → Refresh Token 전체 삭제 → 로그 기록
    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request) {
        String key    = REDIS_PREFIX + request.getToken();
        String userId = redisTemplate.opsForValue().get(key);

        // Redis에 키가 없으면 만료되거나 존재하지 않는 토큰
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_RESET_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 이전 비밀번호 2개와 중복 검사
        checkPasswordHistory(user, request.getNewPassword());

        // 비밀번호 변경 (이력 자동 보관)
        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        // 사용된 토큰 즉시 삭제 — 재사용 방지
        redisTemplate.delete(key);

        // 모든 디바이스 Refresh Token 삭제 — 비밀번호 변경 후 재로그인 강제
        refreshTokenRepository.deleteByUserId(userId);

        // 비밀번호 재설정 로그 기록
        accessLogRepository.save(AccessLog.builder()
                .userId(userId)
                .action("PASSWORD_RESET")
                .build());
    }

    // 이전 비밀번호 2개와 중복 여부 검사
    private void checkPasswordHistory(User user, String newPassword) {
        if (user.getPrevPassword1() != null && passwordEncoder.matches(newPassword, user.getPrevPassword1())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }
        if (user.getPrevPassword2() != null && passwordEncoder.matches(newPassword, user.getPrevPassword2())) {
            throw new CustomException(ErrorCode.PASSWORD_RECENTLY_USED);
        }
    }

    private void sendResetEmail(String to, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 안내");
        message.setText(
                "비밀번호 재설정 토큰: " + token + "\n\n" +
                "유효 시간: 30분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        mailSender.send(message);
    }
}
