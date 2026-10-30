package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * SOS "보호자에게 직접 전화"의 전화받은 보호자를 확인한다(2026-10-30).
 *
 * <p><b>SOS를 거절하지 않는다</b> - 조건을 하나라도 못 맞추면 {@code null}(저장 안 함)과 WARN만 남긴다. 긴급 경로가
 * 부가 정보(이력 화면의 이름 칩) 때문에 실패하면 안 된다.</p>
 *
 * <p><b>트랜잭션 밖에서 부른다</b> - 이 클래스에는 {@code @Transactional}을 걸지 않고, SOS 저장 트랜잭션보다 먼저
 * 호출한다. {@link ConnectionService#isActiveConnection}은 자체 트랜잭션 메서드라, SOS 저장 트랜잭션 안에서 부르다
 * 예외가 나면 잡더라도 바깥 트랜잭션이 rollback-only가 되어 SOS 이력까지 잃는다.</p>
 *
 * <p>로그에는 입력 원문을 남기지 않는다(줄바꿈 삽입·과대 입력 방지) - 피보호자 ID와 사유 코드만.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosCalledGuardianResolver {

    /** 사용자 ID 형식(영숫자 1~6자). 어긋난 값은 DB 조회 없이 버린다. */
    private static final Pattern USER_ID_FORMAT = Pattern.compile("[A-Za-z0-9]{1,6}");

    private final ConnectionService connectionService;

    /**
     * 저장할 전화받은 보호자 ID를 돌려준다.
     *
     * @param wardId           SOS를 보낸 피보호자 ID (인증 주체)
     * @param triggerType      발생 경로. {@code GUARDIAN_CALL}일 때만 값을 받는다({@code null}은 SOS_BUTTON으로 기록되므로 버린다)
     * @param calledGuardianId 클라이언트가 보낸 값
     * @return 그 피보호자의 ACTIVE 보호자로 확인된 ID, 아니면 {@code null}
     */
    public String resolve(String wardId, SosTriggerType triggerType, String calledGuardianId) {
        if (calledGuardianId == null || calledGuardianId.isBlank()) {
            return null;
        }
        if (triggerType != SosTriggerType.GUARDIAN_CALL) {
            log.warn("[SOS-CALLED-GUARDIAN-IGNORED] wardId={}, reason=NOT_GUARDIAN_CALL", wardId);
            return null;
        }
        if (!USER_ID_FORMAT.matcher(calledGuardianId).matches()) {
            log.warn("[SOS-CALLED-GUARDIAN-IGNORED] wardId={}, reason=FORMAT, length={}",
                    wardId, calledGuardianId.length());
            return null;
        }
        try {
            if (!connectionService.isActiveConnection(calledGuardianId, wardId)) {
                log.warn("[SOS-CALLED-GUARDIAN-IGNORED] wardId={}, reason=NOT_CONNECTED", wardId);
                return null;
            }
        } catch (RuntimeException e) {
            log.warn("[SOS-CALLED-GUARDIAN-IGNORED] wardId={}, reason=CHECK_FAILED, error={}",
                    wardId, e.getClass().getSimpleName());
            return null;
        }
        return calledGuardianId;
    }
}
