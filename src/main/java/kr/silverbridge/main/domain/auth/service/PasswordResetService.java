package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.PasswordResetConfirmRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetEmailVerifyRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsSendRequest;
import kr.silverbridge.main.domain.auth.dto.PasswordResetSmsVerifyRequest;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.MaskingUtil;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * 비밀번호 재설정 서비스.
 * <p>
 * 흐름은 6자리 인증코드 하나로 통일한다(UUID 토큰 없음).
 * 1) send  : 이메일/SMS로 6자리 코드 발송
 * 2) verify: 6자리 코드 사전 확인 (코드를 소비하지 않음 — 인증코드 입력 화면의 '확인')
 * 3) reset : 같은 6자리 코드 + 새 비밀번호로 변경 (이때 코드 소비)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final AccessLogService accessLogService;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final SmsVerificationService smsVerificationService;
    private final VerificationCodeValidator verificationCodeValidator;
    private final ApplicationEventPublisher eventPublisher;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PASSWORD_RESET_SMS_TEMPLATE =
            "[SilverBridge] 비밀번호 재설정 인증번호: %s\n유효 시간: 5분";

    // [이메일 방식] 비밀번호 재설정 인증코드 이메일 발송
    // 이메일로 사용자 조회 → 6자리 코드 생성 → 메일 발송 → Redis 저장(5분) → 오류 횟수 초기화
    // 보안을 위해 사용자가 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    // 단일 조회 + Redis + 외부 메일 발송이므로 트랜잭션 미사용 — SMTP 호출이 DB 커넥션을 점유하지 않게 함 (M-5)
    public void requestReset(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // 사용자가 없거나 카카오 사용자면 조용히 종료 (가입 여부 노출 방지)
        if (user == null || user.isSocialProvider()) {
            return;
        }

        String email = user.getEmail();
        VerificationKeyConfig config = VerificationKeyConfig.PASSWORD_RESET_EMAIL;

        // 재발송 쿨다운 없음 — 잘못 눌러도 즉시 재요청 가능. 빈도 방어는 컨트롤러 IP RateLimit에 의존.
        String code = generateCode();
        sendResetEmail(email, code);

        // 인증코드 저장 + 기존 오류 횟수 초기화 (기존 코드가 있으면 새 코드로 교체)
        redisTemplate.opsForValue()
                .set(config.verifyKey(email), code, SmsVerificationService.CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.delete(config.attemptKey(email));

        log.info("비밀번호 재설정 이메일 발송 완료: {}", MaskingUtil.maskEmail(email));
    }

    // [이메일 방식] 인증코드 사전 확인 (코드 미소비)
    // 6자리 코드만 검증하고 소비하지 않는다. 실제 변경은 confirmReset에서 같은 코드로 재검증.
    public void verifyEmailCode(PasswordResetEmailVerifyRequest request) {
        VerificationKeyConfig config = VerificationKeyConfig.PASSWORD_RESET_EMAIL;
        verificationCodeValidator.verifyWithoutConsume(
                config.verifyKey(request.getEmail()),
                config.attemptKey(request.getEmail()),
                request.getCode(),
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS
        );
    }

    // [SMS 방식] 비밀번호 재설정 인증코드 발송
    // 이름+전화번호로 사용자 조회 → 공통 SMS 로직에 위임 (코드저장·발송)
    // 보안을 위해 사용자가 없거나 카카오 사용자여도 동일하게 200 반환 (가입 여부 노출 방지)
    // 단일 조회 + 외부 SMS 발송이므로 트랜잭션 미사용 — 외부 호출이 DB 커넥션을 점유하지 않게 함 (M-5)
    public void requestResetBySms(PasswordResetSmsSendRequest request) {
        String phone = request.getPhone();

        User user = userRepository.findAllByNameAndPhone(request.getName(), phone).stream()
                .filter(User::isLocalProvider)
                .findFirst()
                .orElse(null);

        // 사용자가 없으면 조용히 종료 (가입 여부 노출 방지)
        if (user == null) {
            return;
        }

        smsVerificationService.sendCode(phone, VerificationKeyConfig.PASSWORD_RESET, PASSWORD_RESET_SMS_TEMPLATE);
        log.info("비밀번호 재설정 SMS 발송 완료: {}", MaskingUtil.maskPhone(phone));
    }

    // [SMS 방식] 인증코드 사전 확인 (코드 미소비)
    // 6자리 코드만 검증하고 소비하지 않는다.
    public void verifySmsCode(PasswordResetSmsVerifyRequest request) {
        VerificationKeyConfig config = VerificationKeyConfig.PASSWORD_RESET;
        verificationCodeValidator.verifyWithoutConsume(
                config.verifyKey(request.getPhone()),
                config.attemptKey(request.getPhone()),
                request.getCode(),
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS
        );
    }

    // 새 비밀번호 설정 (이메일/SMS 방식 공통)
    // email 또는 phone 중 정확히 하나로 흐름을 식별 → 6자리 코드 재검증·소비 →
    // 비밀번호 검증 → 변경 → 모든 기기 로그아웃(이벤트) → 로그 기록(IP/UA 포함)
    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request, String ipAddress, String userAgent) {
        boolean byEmail = StringUtils.hasText(request.getEmail());
        boolean byPhone = StringUtils.hasText(request.getPhone());

        // 이메일/전화번호 중 정확히 하나만 허용
        if (byEmail == byPhone) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        VerificationKeyConfig config;
        String identifier;
        User user;

        if (byEmail) {
            config = VerificationKeyConfig.PASSWORD_RESET_EMAIL;
            identifier = request.getEmail();
            user = userRepository.findByEmail(identifier)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        } else {
            config = VerificationKeyConfig.PASSWORD_RESET;
            identifier = request.getPhone();
            user = userRepository.findByPhone(identifier)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        }

        // 카카오 가입 계정은 비밀번호가 없음 (방어적 차단 — 정상 흐름에선 코드도 발급 안 됨)
        if (user.isSocialProvider()) {
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        }

        // 6자리 코드 재검증 + 소비 (성공 시 코드/오류횟수 삭제 — 재사용 방지)
        verificationCodeValidator.verify(
                config.verifyKey(identifier),
                config.attemptKey(identifier),
                request.getCode(),
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS
        );

        // 현재 비밀번호와 동일한 경우 차단
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.SAME_AS_CURRENT_PASSWORD);
        }

        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        // 비밀번호 변경 후 모든 기기 로그아웃 + access token 무효화 (이벤트 통일)
        // listener(AFTER_COMMIT)가 refresh delete + Redis invalidation 도장 처리
        eventPublisher.publishEvent(new PasswordChangedEvent(user.getId()));

        // 비밀번호 재설정 이력 기록 (IP/UA 포함 — 침해 조사 추적성)
        accessLogService.log(user.getId(), AccessAction.PASSWORD_RESET, ipAddress, userAgent);
    }

    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private void sendResetEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[SilverBridge] 비밀번호 재설정 인증번호");
        message.setText(
                "비밀번호 재설정 인증번호: " + code + "\n\n" +
                "유효 시간: 5분\n\n" +
                "본인이 요청하지 않은 경우 이 메일을 무시하세요."
        );
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.error("이메일 발송 실패: {}", e.getMessage());
            // 호출자(requestReset)는 send 성공 후에야 Redis 에 저장하므로 정리할 키 없음
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

}
