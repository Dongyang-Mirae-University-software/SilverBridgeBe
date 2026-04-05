package kr.gosky.sso.domain.auth.service;

import kr.gosky.sso.domain.auth.dto.EmailSendRequest;
import kr.gosky.sso.domain.auth.dto.EmailVerifyRequest;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerifyService {

    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;

    private static final long VERIFY_CODE_TTL = 5L;       // 5분
    private static final String REDIS_PREFIX   = "email:verify:";

    // 이메일 인증 코드 발송
    // 6자리 코드 생성 → Redis 저장(TTL 5분) → 이메일 발송
    @Transactional(readOnly = true)
    public void sendVerificationCode(EmailSendRequest request) {
        String email = request.getEmail();

        // 가입된 사용자 확인
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 이미 인증된 계정 차단
        if (user.isEmailVerified()) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }

        String code = generateCode();

        // Redis에 저장 — 기존 코드가 있으면 덮어씀 (재발송 지원)
        redisTemplate.opsForValue()
                .set(REDIS_PREFIX + email, code, VERIFY_CODE_TTL, TimeUnit.MINUTES);

        sendEmail(email, code);
        log.info("이메일 인증 코드 발송 완료: {}", email);
    }

    // 인증 코드 검증
    // Redis에서 코드 조회 → 일치 확인 → user.emailVerified = true 처리
    @Transactional
    public void verifyCode(EmailVerifyRequest request) {
        String email = request.getEmail();
        String key   = REDIS_PREFIX + email;

        String savedCode = redisTemplate.opsForValue().get(key);

        // Redis에 코드가 없으면 만료된 것
        if (savedCode == null) {
            throw new CustomException(ErrorCode.EXPIRED_VERIFY_CODE);
        }

        // 코드 불일치
        if (!savedCode.equals(request.getCode())) {
            throw new CustomException(ErrorCode.INVALID_VERIFY_CODE);
        }

        // 인증 완료 — Redis 코드 삭제 후 사용자 상태 업데이트
        redisTemplate.delete(key);

        userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND))
                .verifyEmail();
    }

    // 6자리 숫자 인증 코드 생성 (SecureRandom 사용)
    private String generateCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    private void sendEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 이메일 인증 코드");
        message.setText("인증 코드: " + code + "\n\n유효 시간: 5분");
        mailSender.send(message);
    }
}
