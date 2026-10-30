package kr.silverbridge.main.global.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동기 AFTER_COMMIT 리스너 안의 쓰기는 REQUIRES_NEW 여야 한다 (CLAUDE.md §8, 2026-09-30 H-1·M-1).
 *
 * <p>기본 전파면 이미 커밋된 트랜잭션에 합류해 쓰기와 그 안에서 발행한 이벤트가 조용히 사라진다(탈퇴 시 상대 해제 알림이
 * 이 이유로 한 번도 나가지 않았다). 지금은 리스너가 아니라 <b>호출하는 서비스 메서드</b>에 REQUIRES_NEW 를 건 곳이 많아,
 * 누가 리스너에 저장소 쓰기를 직접 넣으면 같은 결함이 다시 생긴다(2026-10 전체 점검 M-9).</p>
 *
 * <p>규칙: {@code @Async} 가 없는 AFTER_COMMIT 리스너는 ① 스스로 REQUIRES_NEW 이거나 ② 아래 {@link #DELEGATES}에
 * 위임 대상(모두 REQUIRES_NEW)을 적거나 ③ {@link #NO_WRITE}에 쓰기가 없다는 이유를 적어야 한다. 새 리스너를 만들면
 * 이 테스트가 실패해 어느 쪽인지 정하게 만든다. 실 DB 동작은 {@code WithdrawalListenerCommitIntegrationTest} 가 검증한다.</p>
 */
class SyncAfterCommitListenerPropagationTest {

    private static final String BASE = "kr.silverbridge.main";

    /** 리스너 메서드 → 그 안에서 부르는 쓰기 메서드(모두 REQUIRES_NEW 여야 한다). */
    private static final Map<String, List<String>> DELEGATES = Map.of(
            "PasswordChangedFcmListener#handlePasswordChanged",
            List.of("kr.silverbridge.main.domain.notification.service.FcmService#deleteAllTokens"),
            "UserWithdrawalFcmListener#handleWithdrawn",
            List.of("kr.silverbridge.main.domain.notification.service.FcmService#deleteAllTokens"),
            "AnomalyClipCleanupListener#handleWithdrawn",
            List.of("kr.silverbridge.main.domain.anomaly.service.AnomalyClipService#deleteAllOfWard"),
            "AnomalyClipCleanupListener#handleCameraDeleted",
            List.of("kr.silverbridge.main.domain.anomaly.service.AnomalyClipService#deleteAllOfCameras"),
            "UserWithdrawalConnectionListener#handleWithdrawn",
            List.of("kr.silverbridge.main.domain.connection.service.ConnectionService#tearDownConnectionsOnWithdrawal"),
            "MedicationWithdrawalListener#handleWithdrawn",
            List.of("kr.silverbridge.main.domain.medication.service.MedicationWithdrawalService#removeMedicationsRegisteredBy"));

    /** 쓰기가 없는 동기 리스너와 그 이유. */
    private static final Map<String, String> NO_WRITE = Map.of(
            "AiLiveStreamSubscriber#onCameraRegistered", "AI WS 에 세션 목록을 요청할 뿐 DB 에 쓰지 않는다");

    @Test
    @DisplayName("@Async 없는 AFTER_COMMIT 리스너는 스스로 REQUIRES_NEW 이거나, 위임 대상이 모두 REQUIRES_NEW 이거나, 쓰기가 없다")
    void sync_after_commit_listeners_write_only_through_requires_new() throws Exception {
        List<String> undeclared = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Method method : syncAfterCommitListeners()) {
            String key = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            seen.add(key);
            if (isRequiresNew(method) || NO_WRITE.containsKey(key)) {
                continue;
            }
            List<String> delegates = DELEGATES.get(key);
            if (delegates == null) {
                undeclared.add(key);
                continue;
            }
            for (String delegate : delegates) {
                assertThat(isRequiresNew(resolve(delegate)))
                        .as("%s 가 부르는 %s 는 REQUIRES_NEW 여야 한다", key, delegate)
                        .isTrue();
            }
        }
        assertThat(undeclared)
                .as("새 동기 AFTER_COMMIT 리스너 - 쓰기를 REQUIRES_NEW 로 하고 이 테스트의 표에 적을 것")
                .isEmpty();
        assertThat(seen).as("표에 적었지만 사라진 리스너가 없어야 한다(낡은 항목 정리)")
                .containsAll(DELEGATES.keySet())
                .containsAll(NO_WRITE.keySet());
    }

    private static List<Method> syncAfterCommitListeners() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Method> result = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(BASE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                TransactionalEventListener listener =
                        AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);
                if (listener == null || listener.phase() != TransactionPhase.AFTER_COMMIT) {
                    continue;
                }
                if (method.isAnnotationPresent(Async.class) || type.isAnnotationPresent(Async.class)) {
                    continue;
                }
                result.add(method);
            }
        }
        assertThat(result).as("스캔이 리스너를 찾아야 한다").isNotEmpty();
        return result;
    }

    private static Method resolve(String delegate) throws ClassNotFoundException {
        String[] parts = delegate.split("#");
        return Arrays.stream(Class.forName(parts[0]).getDeclaredMethods())
                .filter(m -> m.getName().equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new AssertionError("위임 대상 메서드가 없다: " + delegate));
    }

    private static boolean isRequiresNew(Method method) {
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        return tx != null && tx.propagation() == Propagation.REQUIRES_NEW;
    }
}
