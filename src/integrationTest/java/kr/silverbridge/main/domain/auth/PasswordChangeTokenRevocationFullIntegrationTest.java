package kr.silverbridge.main.domain.auth;

import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비밀번호 변경 → 커밋 → {@code UserAccountEventListener.handlePasswordChanged}(동기, REQUIRES_NEW)가 옛 세션을 끊는가
 * (통합 테스트 2단계 시나리오 3, 2026-10 전체 점검 H-2).
 *
 * <p>리스너는 refresh 토큰 삭제(DB)와 access 무효화(Redis)를 한 트랜잭션에서 한다. Redis 예외가 메서드 밖으로 새면
 * REQUIRES_NEW가 롤백돼 <b>이미 지운 refresh 토큰이 되살아난다</b> - 탈취를 의심해 비밀번호를 바꿨는데 옛 세션이 7일
 * 남는다. 목 Redis로는 "예외 → 롤백 → 행 부활"을 볼 수 없어 실제 DB와 실제로 멈춘 Redis가 필요하다.</p>
 *
 * <p>Redis는 본인 확인(잠금 카운터가 Redis를 쓴다)이 끝난 뒤, 커밋 직전에 멈춘다 - 바깥 트랜잭션 안에서
 * {@code changePassword}를 부르고(같은 트랜잭션에 합류) 멈춘 다음 커밋하면 AFTER_COMMIT 리스너만 장애를 맞는다.</p>
 */
class PasswordChangeTokenRevocationFullIntegrationTest extends FullContextIntegrationTest {

    private static final String USER = "GPC001";
    private static final String NEW_PASSWORD = "Itnewpass456!";

    @Autowired private UserService userService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("커밋 순간 Redis가 멈춰 있어도 refresh 토큰 삭제는 커밋된다(H-2) - Redis가 돌아오면 정상 동작")
    void Redis_순단에도_refresh_토큰은_지워진다() {
        saveUser(USER, Role.GUARDIAN, Status.ACTIVE);
        signedIn(USER);
        signedIn(USER); // 두 기기

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userService.changePassword(USER, PASSWORD, NEW_PASSWORD);
            pauseRedis();
        });
        try {
            assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", USER))
                    .as("Redis 무효화가 실패해도 refresh 삭제는 롤백되지 않는다").isZero();
            assertThat(count("SELECT count(*) FROM fcm_token WHERE user_id = ?", USER))
                    .as("FCM 토큰 정리(PasswordChangedFcmListener)도 커밋된다").isZero();
        } finally {
            resumeRedis();
        }
        // 무효화 키는 단언하지 않는다 - 멈춘 동안 보낸 SET이 소켓에 남아 재개 순간 늦게 실행될 수 있다(클라이언트는
        // 이미 시간 초과로 포기한 뒤). 이 시나리오가 고정하는 것은 "Redis 예외가 refresh 삭제를 되돌리지 않는다"뿐이다.
    }

    @Test
    @DisplayName("정상일 때: refresh·FCM 토큰 삭제, access 무효화 키, 로그인 잠금 해제가 모두 일어난다")
    void 정상_경로는_모든_정리를_한다() {
        saveUser(USER, Role.GUARDIAN, Status.ACTIVE);
        signedIn(USER);
        redis.opsForValue().set(RedisKeys.LOGIN_FAIL + USER, "3");

        userService.changePassword(USER, PASSWORD, NEW_PASSWORD);

        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", USER)).isZero();
        assertThat(count("SELECT count(*) FROM fcm_token WHERE user_id = ?", USER)).isZero();
        assertThat(redis.hasKey(RedisKeys.PASSWORD_INVALIDATE + USER)).as("access 무효화 키").isTrue();
        assertThat(redis.hasKey(RedisKeys.LOGIN_FAIL + USER)).as("로그인 실패 횟수 해제").isFalse();
    }
}
