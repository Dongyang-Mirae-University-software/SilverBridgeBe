package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카메라 식별자 발급기.
 *
 * <p>SessionID는 AI 서버·영상 경로로 바깥에 보이는 값이라 <b>사용자 식별자를 싣지 않는다</b>(2026-10-05 QA 종합 점검).
 * 예전 형식 {@code ward_{wardId}_{6}}은 세션 ID만으로 피보호자 ID를 알아내 AI 서버의 다른 기록을 조회하는 연쇄를 열었다.</p>
 */
@ExtendWith(MockitoExtension.class)
class CameraIdentifierFactoryTest {

    @Mock
    private CameraRepository cameraRepository;

    @InjectMocks
    private CameraIdentifierFactory factory;

    @Test
    @DisplayName("SessionID는 ward_ + 영숫자 16자다 - 피보호자 ID 자리가 없다")
    void sessionIdFormat() {
        String id = factory.newSessionId();

        assertThat(id).matches("^ward_[A-Za-z0-9]{16}$");
        assertThat(id.length()).as("camera.session_id VARCHAR(64) 안").isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("SessionID는 매번 다르다(16자 랜덤)")
    void sessionIdIsRandom() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(factory.newSessionId());
        }

        assertThat(ids).hasSize(200);
    }

    @Test
    @DisplayName("이미 있는 SessionID가 뽑히면 다시 뽑는다")
    void sessionIdRetriesOnCollision() {
        when(cameraRepository.existsBySessionId(anyString())).thenReturn(true, false);

        String id = factory.newSessionId();

        ArgumentCaptor<String> checked = ArgumentCaptor.forClass(String.class);
        verify(cameraRepository, times(2)).existsBySessionId(checked.capture());
        assertThat(checked.getAllValues().get(1)).isEqualTo(id);
        assertThat(checked.getAllValues().get(0)).isNotEqualTo(id);
    }

    @Test
    @DisplayName("DeviceID는 dev_ + 영숫자 10자다")
    void deviceIdFormat() {
        assertThat(factory.newDeviceId()).matches("^dev_[A-Za-z0-9]{10}$");
    }
}
