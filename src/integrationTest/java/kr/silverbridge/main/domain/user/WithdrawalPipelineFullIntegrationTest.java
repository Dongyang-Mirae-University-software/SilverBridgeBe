package kr.silverbridge.main.domain.user;

import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.service.UserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.support.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.firebase.messaging.MulticastMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 탈퇴 2단계(상태 변경 + 이벤트 커밋 → 영구 삭제)를 실제 진입점부터 끝까지 (통합 테스트 2단계 시나리오 1·2).
 *
 * <p>CLAUDE.md §8 "회원 탈퇴 = hard delete, 정리는 {@code UserWithdrawnEvent} AFTER_COMMIT, 관리자 강제 탈퇴도 같은
 * 파이프라인". 1단계 {@code WithdrawalListenerCommitIntegrationTest}는 리스너 몇 개를 골라 이벤트를 직접 발행했다 -
 * 여기서는 컨트롤러가 하는 순서({@code withdraw} → {@code purgeWithdrawnUser}) 그대로 부르고, 앱에 등록된
 * <b>모든</b> 탈퇴 리스너와 알림 디스패처가 실제로 돈다.</p>
 *
 * <p>상대방 해제 알림은 탈퇴 리스너(동기, REQUIRES_NEW) 안에서 발행한 연결 해제 이벤트가 다시 AFTER_COMMIT을 맞아야
 * 나간다. 전파가 REQUIRED면 이미 커밋된 탈퇴 트랜잭션에 합류해 해제도 알림도 사라진다(H-1, 2026-09-30).</p>
 */
class WithdrawalPipelineFullIntegrationTest extends FullContextIntegrationTest {

    private static final String GUARDIAN = "GWP001";
    private static final String WARD = "WWP001";
    private static final String ADMIN = "AWP001";

    @Autowired private UserService userService;
    @Autowired private AdminUserService adminUserService;

    @Test
    @DisplayName("본인 탈퇴: 커밋 직후 토큰·FCM이 정리되고 연결이 해제되며, 영구 삭제 뒤 상대 보호자에게 해제 알림과 AI 기록 삭제 요청이 간다")
    void 본인_탈퇴는_전체_파이프라인을_탄다() throws Exception {
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        Long connectionId = saveActiveConnection(GUARDIAN, WARD);
        signedIn(WARD);
        signedIn(GUARDIAN);

        userService.withdraw(WARD, PASSWORD, null, "127.0.0.1", "it");

        // 1단계 커밋 직후(영구 삭제 전) - 동기 리스너의 쓰기가 실제로 커밋됐는가
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", WARD))
                .as("탈퇴자 refresh 토큰 삭제").isZero();
        assertThat(count("SELECT count(*) FROM fcm_token WHERE user_id = ?", WARD))
                .as("탈퇴자 FCM 토큰 삭제").isZero();
        assertThat(redis.hasKey(RedisKeys.PASSWORD_INVALIDATE + WARD))
                .as("기존 access 토큰 무효화 키").isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM connection WHERE id = ?", String.class, connectionId))
                .as("연결 해제가 커밋됨(H-1)").isEqualTo("DISCONNECTED");

        userService.purgeWithdrawnUser(WARD);

        assertThat(userRepository.existsById(WARD)).as("hard delete").isFalse();
        // 상대 보호자에게 해제 푸시가 나간다. 이력 행은 남지 않는다 - 이력의 ward_id가 이미 지워진 피보호자를 가리켜
        // FK 위반으로 기록이 실패한다([NOTIFY-LOG-FAILED] WARN, 발송은 그대로). FK가 ON DELETE CASCADE라 먼저 기록됐어도
        // purge 때 함께 지워지므로 결과는 설계와 같다(2026-10-31 2단계 테스트에서 확인, 설계 문서 PHASE 4 🟢).
        // 탈퇴자의 FCM 토큰은 이미 지워졌으므로 남은 발송 대상은 상대 보호자뿐이다.
        verify(firebaseMessaging, timeout(10_000)).sendEachForMulticast(any(MulticastMessage.class));
        verify(aiChatClient, timeout(10_000)).deleteLogs(WARD);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE user_id = ?", GUARDIAN))
                .as("상대방 토큰은 그대로").isEqualTo(1);
    }

    @Test
    @DisplayName("관리자 강제 탈퇴: 같은 파이프라인을 타서 상대 피보호자 해제 알림·토큰 정리가 일어나고, 감사 로그가 남는다")
    void 관리자_강제_탈퇴도_같은_파이프라인을_탄다() {
        saveUser(ADMIN, Role.ADMIN, Status.ACTIVE);
        saveUser(GUARDIAN, Role.GUARDIAN, Status.ACTIVE);
        saveUser(WARD, Role.WARD, Status.ACTIVE);
        saveActiveConnection(GUARDIAN, WARD);
        signedIn(GUARDIAN);

        adminUserService.forceDelete(GUARDIAN, ADMIN, "127.0.0.1", "it");

        assertThat(userRepository.existsById(GUARDIAN)).as("hard delete").isFalse();
        assertThat(redis.hasKey(RedisKeys.PASSWORD_INVALIDATE + GUARDIAN))
                .as("강제 탈퇴자의 기존 access 토큰 무효화 - delete() 직접 호출이면 남지 않는다").isTrue();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE admin_id = ? AND action = 'USER_FORCE_DELETE' "
                + "AND target_id = ?", ADMIN, GUARDIAN)).as("감사 로그").isEqualTo(1);
        eventually().untilAsserted(() -> assertThat(notificationLogs(WARD, NotificationType.CONNECTION_DISCONNECTED))
                .as("상대 피보호자에게 해제 알림(이력)").isEqualTo(1));
        verify(aiChatClient, timeout(10_000)).deleteLogs(GUARDIAN);
    }
}
