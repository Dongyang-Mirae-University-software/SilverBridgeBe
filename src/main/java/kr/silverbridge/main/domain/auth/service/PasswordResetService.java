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
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
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
import java.util.List;
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
    private final RedisCounter redisCounter;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PASSWORD_RESET_SMS_TEMPLATE =
            "[SilverBridge] 비밀번호 재설정 인증번호: %s\n유효 시간: 5분";

    /** per-email 발송 상한 윈도우 (초) — 1시간 */
    private static final long EMAIL_SEND_CAP_WINDOW_SECONDS = 3600L;
    /** per-email 발송 상한 (윈도우당 최대 발송 건수) — IP 우회 메일 폭탄·비용 남용 차단 (SMS A-M3 대칭, 2026-05-23) */
    private static final long MAX_EMAIL_SENDS_PER_WINDOW = 10L;

    // [이메일 방식] 비밀번호 재설정 인증코드 이메일 발송
    // 이메일로 사용자 조회 → (미가입 404 / 카카오 400) → per-email 발송 상한 → 6자리 코드 생성 →
    // 메일 발송 → Redis 저장(5분) → 오류 횟수 초기화
    //
    // 정책 변경(2026-05-23): 시니어/4050 타겟 UX 우선 — 미가입/카카오에 always-200 대신 명시적 응답을 준다.
    //   "메일이 안 와요" 이탈을 줄이는 대신, enumeration 노출은 IP 이중 윈도우 RateLimit(컨트롤러) +
    //   per-email 발송 상한(아래) + 미가입 WARN 로깅으로 방어한다.
    // 단일 조회 + Redis + 외부 메일 발송이므로 트랜잭션 미사용 — SMTP 호출이 DB 커넥션을 점유하지 않게 함 (M-5)
    public void requestReset(PasswordResetRequest request, String ipAddress) {
        String email = request.getEmail();
        User user = userRepository.findByEmail(email).orElse(null);

        // 미가입 → 404로 명확히 안내. enumeration 스윕 사후 탐지를 위해 WARN 로깅(마스킹+IP).
        if (user == null) {
            log.warn("[PW-RESET] 미가입 이메일 재설정 요청 차단 — email={}, ip={}",
                    MaskingUtil.maskEmail(email), ipAddress);
            throw new CustomException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND);
        }
        // 카카오 가입 계정은 비밀번호가 없음 → 카카오 로그인 사용하도록 400 안내
        if (user.isSocialProvider()) {
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        }

        String resolvedEmail = user.getEmail();

        // per-email 발송 상한 — IP 회전으로 IP RateLimit을 우회해 특정 이메일로 메일 폭탄·비용 남용하는 것을
        // 차단 (SMS sendCode의 A-M3와 대칭). 가입 계정 확인 후에만 카운트해 미존재 이메일로 키가 늘지 않게 한다.
        long sent = redisCounter.incrementWithTtl(
                RedisKeys.PW_EMAIL_SEND_COUNT + resolvedEmail, EMAIL_SEND_CAP_WINDOW_SECONDS);
        if (sent > MAX_EMAIL_SENDS_PER_WINDOW) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }

        VerificationKeyConfig config = VerificationKeyConfig.PASSWORD_RESET_EMAIL;

        // 재발송 쿨다운 없음 — 잘못 눌러도 즉시 재요청 가능. 빈도 방어는 IP RateLimit + per-email 상한에 의존.
        String code = generateCode();
        sendResetEmail(resolvedEmail, code);

        // 인증코드 저장 + 기존 오류 횟수 초기화 (기존 코드가 있으면 새 코드로 교체)
        redisTemplate.opsForValue()
                .set(config.verifyKey(resolvedEmail), code, SmsVerificationService.CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.delete(config.attemptKey(resolvedEmail));

        log.info("비밀번호 재설정 이메일 발송 완료: {}", MaskingUtil.maskEmail(resolvedEmail));
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
    // 이름+전화번호로 사용자 조회 → (미일치 404 / 카카오만 매칭 400) → 공통 SMS 로직에 위임 (코드저장·발송)
    //
    // 정책 변경(2026-05-23): 시니어/4050 타겟 UX 우선 — 미일치/카카오에 always-200 대신 명시적 응답을 준다.
    //   SMS 비용 보호(#4): 미일치(404)는 SmsSender 호출 전에 차단되어 미가입자에게 SMS가 발송되지 않는다.
    //   특정 실사용 번호로의 SMS 폭탄은 sendCode 내부 per-phone 상한(A-M3)이 별도로 막는다.
    // 단일 조회 + 외부 SMS 발송이므로 트랜잭션 미사용 — 외부 호출이 DB 커넥션을 점유하지 않게 함 (M-5)
    public void requestResetBySms(PasswordResetSmsSendRequest request, String ipAddress) {
        String phone = request.getPhone();

        List<User> matches = userRepository.findAllByNameAndPhone(request.getName(), phone);
        User user = matches.stream()
                .filter(User::isLocalProvider)
                .findFirst()
                .orElse(null);

        if (user == null) {
            // 매칭 자체가 없으면 미가입(404), 카카오 계정만 있으면 카카오 안내(400)로 구분
            if (matches.isEmpty()) {
                log.warn("[PW-RESET] 미일치 SMS 재설정 요청 차단 — phone={}, ip={}",
                        MaskingUtil.maskPhone(phone), ipAddress);
                throw new CustomException(ErrorCode.USER_NOT_FOUND);
            }
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
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

        VerificationKeyConfig config = byEmail
                ? VerificationKeyConfig.PASSWORD_RESET_EMAIL
                : VerificationKeyConfig.PASSWORD_RESET;
        String identifier = byEmail ? request.getEmail() : request.getPhone();

        // 6자리 코드를 사용자 조회보다 "먼저" 재검증한다 (A-M1, 계정 enumeration 차단).
        // 미가입/카카오 계정에는 애초에 코드가 발급되지 않으므로(requestReset 가 조용히 종료) 여기서
        // EXPIRED_SMS_CODE 로 동일하게 막혀, 가입 여부·provider 가 응답으로 새지 않는다.
        //
        // ⚠️ 검증과 소비를 분리한다 — 여기서는 소비하지 않는다(verifyWithoutConsume).
        // verify(소비형)를 먼저 호출하면, 이후 SAME_AS_CURRENT_PASSWORD 등 다운스트림 검증이 실패할 때
        // Redis 삭제가 @Transactional 롤백 대상이 아니라 코드가 비가역적으로 소모돼 같은 코드로 재시도가 막힌다.
        // 회원가입 nonce 소비(AuthService/KakaoAuthService)와 동일하게 "검증 후 마지막 소비" 순서를 맞춘다.
        verificationCodeValidator.verifyWithoutConsume(
                config.verifyKey(identifier),
                config.attemptKey(identifier),
                request.getCode(),
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS
        );

        // 유효한 코드 보유자만 도달 — 코드는 가입된 LOCAL 사용자에게만 발급되므로 아래 분기는 정상 흐름에서 항상 통과.
        User user = (byEmail
                ? userRepository.findByEmail(identifier)
                : userRepository.findByPhone(identifier))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 카카오 가입 계정은 비밀번호가 없음 (방어적 차단 — 정상 흐름에선 코드도 발급 안 됨)
        if (user.isSocialProvider()) {
            throw new CustomException(ErrorCode.SOCIAL_USER_NO_PASSWORD);
        }

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

        // 모든 검증·처리가 성공한 "마지막"에 인증코드 소비 — 1회용 재사용 방지.
        // 중간 단계가 실패하면 코드가 그대로 남아 같은 코드로 즉시 재시도할 수 있다(자연 TTL/5회 한도까지).
        verificationCodeValidator.consume(config.verifyKey(identifier), config.attemptKey(identifier));
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
