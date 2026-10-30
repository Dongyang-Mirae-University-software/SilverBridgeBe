package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 전화받은 보호자 확인 - 어떤 입력에도 예외 없이 "저장할 값 또는 null"만 돌려준다(SOS를 거절하지 않는다).
 */
@ExtendWith(MockitoExtension.class)
class SosCalledGuardianResolverTest {

    private static final String WARD_ID = "WD0001";
    private static final String GUARDIAN_ID = "GD0001";

    @Mock private ConnectionService connectionService;

    @InjectMocks private SosCalledGuardianResolver resolver;

    @Test
    @DisplayName("GUARDIAN_CALL + ACTIVE 보호자 → 그 ID를 저장한다")
    void 연결된_보호자() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(true);

        assertThat(resolver.resolve(WARD_ID, SosTriggerType.GUARDIAN_CALL, GUARDIAN_ID)).isEqualTo(GUARDIAN_ID);
    }

    @Test
    @DisplayName("연결되지 않은 보호자(남의 보호자·해제·PENDING) → null")
    void 연결안된_보호자() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID)).thenReturn(false);

        assertThat(resolver.resolve(WARD_ID, SosTriggerType.GUARDIAN_CALL, GUARDIAN_ID)).isNull();
    }

    @Test
    @DisplayName("연결 확인이 실패해도 예외를 내보내지 않고 null - SOS 접수는 계속된다")
    void 확인_실패() {
        when(connectionService.isActiveConnection(GUARDIAN_ID, WARD_ID))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(resolver.resolve(WARD_ID, SosTriggerType.GUARDIAN_CALL, GUARDIAN_ID)).isNull();
    }

    @Test
    @DisplayName("SOS 버튼·경로 생략에 같이 온 값은 조회 없이 버린다")
    void 보호자연락이_아니면_버린다() {
        assertThat(resolver.resolve(WARD_ID, SosTriggerType.SOS_BUTTON, GUARDIAN_ID)).isNull();
        assertThat(resolver.resolve(WARD_ID, null, GUARDIAN_ID)).isNull();
        verifyNoInteractions(connectionService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "GD00011", "GD-001", "GD\n001", "가나다"})
    @DisplayName("비었거나 형식(영숫자 1~6자)이 틀린 값은 조회 없이 null")
    void 형식_오류(String value) {
        assertThat(resolver.resolve(WARD_ID, SosTriggerType.GUARDIAN_CALL, value)).isNull();
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("확인기는 트랜잭션을 걸지 않는다 - SOS 저장 트랜잭션 밖에서 불려야 확인 실패가 이력을 롤백시키지 않는다")
    void 트랜잭션_없음() {
        assertThat(SosCalledGuardianResolver.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(java.util.Arrays.stream(SosCalledGuardianResolver.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(Transactional.class))).isFalse();
    }
}
