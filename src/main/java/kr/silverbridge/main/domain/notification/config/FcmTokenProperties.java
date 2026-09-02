package kr.silverbridge.main.domain.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FCM 토큰 보관 정책 (application.yaml {@code notification.fcm-token.*}).
 *
 * <p>토큰은 <b>기기 단위</b>라 한 사람이 여러 개를 갖는 것이 정상이다(폰·PC·태블릿). 다만 브라우저
 * 데이터 삭제·시크릿창·기기 교체로 만들어진 토큰은 스스로 사라지지 않아, 두면 쓰지 않는 기기로
 * 알림이 흩어져 나간다. 상한과 유휴 기간으로 그 잔여물만 걷어낸다.</p>
 *
 * <p>⚠️ 토큰 삭제는 되돌릴 수 없다 — 지운 기기는 <b>다시 접속할 때까지</b> 푸시를 받지 못한다.
 * 프론트가 보호 라우트 진입 시 재등록하므로 자동 복구되지만, 그 사이 이상감지 알림은
 * SMS 폴백이 없어 조용히 유실될 수 있다. 기준을 조일 때 이 점을 먼저 따질 것.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "notification.fcm-token")
public class FcmTokenProperties {

    /**
     * 사용자당 보관할 토큰 수 상한. 초과하면 <b>마지막 사용이 가장 오래된 것부터</b> 지운다.
     *
     * <p>폰·PC·태블릿에 예비를 더한 값이다. 이보다 낮추면 실제로 쓰는 기기가 잘려 나갈 수 있다.</p>
     */
    private int maxPerUser = 5;

    /** 유휴 토큰 정리. false면 정리만 멈춘다 — 등록·발송은 그대로 동작해야 한다. */
    private boolean cleanupEnabled = true;

    /**
     * 이 일수 동안 <b>한 번도 갱신되지 않은</b> 토큰을 유휴로 본다.
     *
     * <p>갱신은 재등록 때 일어난다(새 탭·재접속마다). 즉 이 값은 "그 브라우저로 이만큼 접속하지
     * 않았다"는 뜻이다. Google도 두 달 이상 갱신 없는 토큰의 삭제를 권장한다.</p>
     */
    private int staleDays = 60;
}
