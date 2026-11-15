package kr.silverbridge.main.domain.connection.controller;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 연결 대상 미리보기의 ID 형식 검사 - 실제 요청(ConnectionRequestDto)과 같이 6자가 아니면 DB 조회 없이 400.
 * (최종 QA CONN-A03, 2026-11-15 - 빈 값·5자·7자가 404 USER_NOT_FOUND 로 나가던 회귀)
 */
class GuardianConnectionControllerPreviewTest {

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final GuardianConnectionController controller =
            new GuardianConnectionController(connectionService, rateLimitService);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "AB123", "AB12345"})
    @DisplayName("빈 값·공백·6자가 아닌 ID 는 400 INVALID_INPUT 이고 회원 조회를 하지 않는다 (속도 제한은 먼저 센다)")
    void 형식_오류는_400(String targetId) {
        assertThatThrownBy(() -> controller.previewConnectionTarget("GD0001", targetId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(rateLimitService).check("connection-preview", "GD0001", 10, 60);
        verify(connectionService, never()).previewConnectionTarget(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB1234"})
    @DisplayName("6자 ID 는 그대로 서비스로 넘긴다")
    void 여섯자는_조회(String targetId) {
        controller.previewConnectionTarget("GD0001", targetId);

        verify(connectionService).previewConnectionTarget("GD0001", targetId);
    }
}
