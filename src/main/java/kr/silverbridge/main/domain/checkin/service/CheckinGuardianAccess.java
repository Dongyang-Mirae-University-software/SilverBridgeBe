package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 보호자 말벗 경로 인가(질문·알림 받기·조회 공용). ACTIVE 연결만({@code isActiveConnection} - {@code getMyWards}는 PENDING이
 * 섞여 금지), 위반 403 {@code CHECKIN_NOT_AUTHORIZED} + {@code [IDOR-ATTEMPT]}. 형식이 틀린 wardId는 원문을 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinGuardianAccess {

    private final ConnectionService connectionService;

    public void require(String guardianId, String wardId, String action) {
        if (!CheckinIds.isUserIdFormat(wardId)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 안부 말벗 접근 시도: guardianId={}, wardIdLength={}, action={}",
                    guardianId, CheckinIds.lengthOf(wardId), action);
            throw new CustomException(ErrorCode.CHECKIN_NOT_AUTHORIZED);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 안부 말벗 접근 시도: guardianId={}, wardId={}, action={}",
                    guardianId, wardId, action);
            throw new CustomException(ErrorCode.CHECKIN_NOT_AUTHORIZED);
        }
    }

    /** 지금 연결이 맺어진 뒤의 기록인지(2026-10-31 D-1 - SOS·이상감지와 같은 규칙). */
    public boolean visible(String guardianId, String wardId, OffsetDateTime at) {
        return connectionService.isVisibleSinceConnection(guardianId, wardId, at);
    }
}
