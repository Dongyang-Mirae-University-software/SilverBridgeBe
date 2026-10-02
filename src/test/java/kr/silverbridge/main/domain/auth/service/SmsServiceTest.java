package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 회원가입 SMS nonce 소비 (AUTH-G16 - 비교+삭제 원자화). */
@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SmsVerificationService smsVerificationService;

    @InjectMocks private SmsService smsService;

    private static final String PHONE = "01012345678";
    private static final String KEY = RedisKeys.SMS_VERIFIED + PHONE;

    @Test
    @DisplayName("AUTH-G16: nonce 소비는 GET/DELETE 두 번이 아니라 스크립트 한 번(비교+삭제)으로 한다")
    void consumeIsSingleAtomicCall() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("nonce-1"))).thenReturn(1L);

        assertThatCode(() -> smsService.consumeVerification(PHONE, "nonce-1")).doesNotThrowAnyException();

        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).delete(KEY);
    }

    @Test
    @DisplayName("AUTH-G16: 같은 nonce로 두 요청(더블클릭) → 하나만 성공, 다른 하나는 SMS_NOT_VERIFIED")
    void doubleSubmitOnlyOneSucceeds() {
        // Redis 원자 스크립트를 흉내 - 저장값이 같을 때만 지우고 1
        AtomicReference<String> stored = new AtomicReference<>("nonce-1");
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("nonce-1")))
                .thenAnswer(inv -> stored.compareAndSet("nonce-1", null) ? 1L : 0L);

        int success = 0;
        int notVerified = 0;
        for (int i = 0; i < 2; i++) {
            try {
                smsService.consumeVerification(PHONE, "nonce-1");
                success++;
            } catch (CustomException e) {
                if (e.getErrorCode() == ErrorCode.SMS_NOT_VERIFIED) notVerified++;
            }
        }

        assertThat(success).isEqualTo(1);
        assertThat(notVerified).isEqualTo(1);
    }

    @Test
    @DisplayName("AUTH-G16: 틀린 nonce → SMS_NOT_VERIFIED, 스크립트가 0을 돌려줘 저장된 인증 상태는 지워지지 않는다")
    void wrongNonceDoesNotWipeVerifiedState() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("wrong"))).thenReturn(0L);

        assertThatThrownBy(() -> smsService.consumeVerification(PHONE, "wrong"))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_NOT_VERIFIED);
        verify(redisTemplate, never()).delete(KEY);
    }

    @Test
    @DisplayName("nonce 누락 → Redis 조회 없이 SMS_NOT_VERIFIED")
    void blankNonceRejectedWithoutRedis() {
        assertThatThrownBy(() -> smsService.consumeVerification(PHONE, " "))
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SMS_NOT_VERIFIED);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }
}
