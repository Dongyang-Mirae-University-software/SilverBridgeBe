package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.client.SmsSender;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.util.VerificationCodeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private RedisCounter redisCounter;

    @InjectMocks
    private SmsVerificationService smsVerificationService;

    private static final String PHONE = "01012345678";
    private static final String TEMPLATE = "[SilverBridge] 인증번호: %s\n유효 시간: 5분";

    @Test
    @DisplayName("재발송 쿨다운 없음 — 호출 시 항상 SMS 발송 + 인증키 저장(TTL 5분) + 오류 카운터 삭제")
    void sendCodeStoresKeysWithoutCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE);

        // SMS 본문에 6자리 인증코드가 치환되어 발송됨
        verify(smsSender).send(eq(PHONE), matches("^\\[SilverBridge] 인증번호: \\d{6}\n유효 시간: 5분$"));

        // 인증코드 저장 (TTL 5분) — 쿨다운 키는 더 이상 설정하지 않음
        verify(valueOperations).set(
                eq(VerificationKeyConfig.SIGNUP.verifyKey(PHONE)),
                anyString(),
                eq(SmsVerificationService.CODE_TTL_MINUTES),
                eq(TimeUnit.MINUTES));

        // 기존 오류 카운터 초기화
        verify(redisTemplate).delete(VerificationKeyConfig.SIGNUP.attemptKey(PHONE));
    }

    @Test
    @DisplayName("per-phone 발송 상한 초과 시 TOO_MANY_REQUESTS — SMS 미발송 (A-M3)")
    void sendCodeBlockedWhenPhoneSendCapExceeded() {
        // 윈도우당 상한(10) 초과 → 11번째 발송 시도
        when(redisCounter.incrementWithTtl(eq(RedisKeys.SMS_SEND_COUNT + PHONE), anyLong()))
                .thenReturn(11L);

        assertThatThrownBy(() ->
                smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

        // 상한 초과 시 실제 SMS는 발송되지 않는다
        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("AUTH-G11: 발송 상한 초과 429에 남은 윈도우 시간(retryAfterSeconds)을 싣는다")
    void sendCapExceededCarriesRetryAfter() {
        String key = RedisKeys.SMS_SEND_COUNT + PHONE;
        when(redisCounter.incrementWithTtl(eq(key), anyLong())).thenReturn(11L);
        when(redisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(1234L);

        assertThatThrownBy(() ->
                smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .isInstanceOf(TooManyRequestsException.class)
                .extracting("retryAfterSeconds")
                .isEqualTo(1234L);
    }

    @Test
    @DisplayName("AUTH-G11: TTL을 읽지 못하면 윈도우 길이(3600초)로 안내한다")
    void sendCapExceededFallsBackToWindow() {
        String key = RedisKeys.SMS_SEND_COUNT + PHONE;
        when(redisCounter.incrementWithTtl(eq(key), anyLong())).thenReturn(11L);
        when(redisTemplate.getExpire(key, TimeUnit.SECONDS)).thenReturn(-2L);

        assertThatThrownBy(() ->
                smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .extracting("retryAfterSeconds")
                .isEqualTo(3600L);
    }

    @Test
    @DisplayName("AUTH-G11: SMS 발송 실패 → 발송 상한 예약을 되돌리고 원래 오류(SMS_SEND_FAILED)를 그대로 던진다, 코드 미저장")
    void sendFailureReleasesQuota() {
        String key = RedisKeys.SMS_SEND_COUNT + PHONE;
        when(redisCounter.incrementWithTtl(eq(key), anyLong())).thenReturn(3L);
        doThrow(new CustomException(ErrorCode.SMS_SEND_FAILED)).when(smsSender).send(anyString(), anyString());

        assertThatThrownBy(() ->
                smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_SEND_FAILED);

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("AUTH-G11: 환불 자체가 Redis 오류여도 원래 발송 오류를 응답한다")
    void sendFailureReleaseErrorIsSwallowed() {
        String key = RedisKeys.SMS_SEND_COUNT + PHONE;
        when(redisCounter.incrementWithTtl(eq(key), anyLong())).thenReturn(3L);
        doThrow(new CustomException(ErrorCode.SMS_SEND_FAILED)).when(smsSender).send(anyString(), anyString());
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(key))))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() ->
                smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_SEND_FAILED);
    }

    @Test
    @DisplayName("AUTH-G11: 발송 성공 시에는 예약을 되돌리지 않는다")
    void sendSuccessKeepsQuota() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        smsVerificationService.sendCode(PHONE, VerificationKeyConfig.SIGNUP, TEMPLATE);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
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

        assertThat(signupVerify).isNotEqualTo(resetVerify);
        assertThat(signupVerify).startsWith("sms:verify:");
        assertThat(resetVerify).startsWith("password:sms:verify:");
    }
}
