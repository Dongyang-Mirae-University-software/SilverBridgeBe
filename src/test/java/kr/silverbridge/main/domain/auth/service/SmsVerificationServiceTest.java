package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsVerificationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private VerificationCodeValidator verificationCodeValidator;

    @Mock
    private SmsSender smsSender;

    @InjectMocks
    private SmsVerificationService smsVerificationService;

    private static final String PHONE = "01012345678";
    private static final String TEMPLATE = "[SilverBridge] 인증번호: %s\n유효 시간: 5분";

    @Test
    @DisplayName("쿨다운 키가 있으면 SMS_SEND_TOO_FREQUENT 예외")
    void cooldownBlocksSend() {
        when(redisTemplate.hasKey(VerificationKeyConfig.SIGNUP.cooldownKey(PHONE))).thenReturn(true);

        assertThatThrownBy(() -> smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_SEND_TOO_FREQUENT);

        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("쿨다운이 없으면 SMS 발송 + 인증키/쿨다운키 저장 + 오류 카운터 삭제")
    void sendCodeStoresAllKeys() {
        when(redisTemplate.hasKey(VerificationKeyConfig.SIGNUP.cooldownKey(PHONE))).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE);

        // SMS 본문에 6자리 인증코드가 치환되어 발송됨
        verify(smsSender).send(eq(PHONE), matches("^\\[SilverBridge] 인증번호: \\d{6}\n유효 시간: 5분$"));

        // 인증코드 저장 (TTL 5분)
        verify(valueOperations).set(
                eq(VerificationKeyConfig.SIGNUP.verifyKey(PHONE)),
                anyString(),
                eq(SmsVerificationService.CODE_TTL_MINUTES),
                eq(TimeUnit.MINUTES));

        // 쿨다운 설정 (TTL 1분)
        verify(valueOperations).set(
                eq(VerificationKeyConfig.SIGNUP.cooldownKey(PHONE)),
                eq("1"),
                eq(SmsVerificationService.COOLDOWN_TTL_MINUTES),
                eq(TimeUnit.MINUTES));

        // 기존 오류 카운터 초기화
        verify(redisTemplate).delete(VerificationKeyConfig.SIGNUP.attemptKey(PHONE));
    }

    @Test
    @DisplayName("verifyCode는 VerificationCodeValidator에 위임")
    void verifyCodeDelegatesToValidator() {
        smsVerificationService.verifyCode(PHONE, VerificationKeyConfig.PASSWORD_RESET, "123456");

        verify(verificationCodeValidator).verify(
                VerificationKeyConfig.PASSWORD_RESET.verifyKey(PHONE),
                VerificationKeyConfig.PASSWORD_RESET.attemptKey(PHONE),
                "123456",
                SmsVerificationService.CODE_TTL_MINUTES,
                SmsVerificationService.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("VerificationKeyConfig.SIGNUP과 PASSWORD_RESET은 서로 다른 Redis 키를 생성한다")
    void keyConfigsAreIsolated() {
        String signupVerify = VerificationKeyConfig.SIGNUP.verifyKey(PHONE);
        String resetVerify = VerificationKeyConfig.PASSWORD_RESET.verifyKey(PHONE);

        org.assertj.core.api.Assertions.assertThat(signupVerify).isNotEqualTo(resetVerify);
        org.assertj.core.api.Assertions.assertThat(signupVerify).startsWith("sms:verify:");
        org.assertj.core.api.Assertions.assertThat(resetVerify).startsWith("password:sms:verify:");
    }
}
