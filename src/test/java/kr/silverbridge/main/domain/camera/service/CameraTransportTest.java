package kr.silverbridge.main.domain.camera.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CameraTransportTest {

    @Test
    @DisplayName("목록에 든 sessionId만 ws-h264, 그 밖은 jpeg")
    void 목록_기준() {
        assertThat(CameraTransport.resolve("ward_a", "ward_a,ward_b")).isEqualTo(CameraTransport.WS_H264);
        assertThat(CameraTransport.resolve("ward_c", "ward_a,ward_b")).isEqualTo(CameraTransport.JPEG);
    }

    @Test
    @DisplayName("기본(비어 있음·null·공백)은 전부 jpeg - 플래그를 안 건 서버는 지금과 같다")
    void 기본값() {
        assertThat(CameraTransport.resolve("ward_a", "")).isEqualTo(CameraTransport.JPEG);
        assertThat(CameraTransport.resolve("ward_a", null)).isEqualTo(CameraTransport.JPEG);
        assertThat(CameraTransport.resolve("ward_a", "  ")).isEqualTo(CameraTransport.JPEG);
        assertThat(CameraTransport.resolve(null, "ward_a")).isEqualTo(CameraTransport.JPEG);
    }

    @Test
    @DisplayName("공백·빈 항목은 걸러내고, 부분 문자열은 일치로 보지 않는다")
    void 파싱() {
        assertThat(CameraTransport.parseSessionIds(" ward_a , ,ward_b,,")).containsExactlyInAnyOrder("ward_a", "ward_b");
        assertThat(CameraTransport.resolve("ward_", "ward_a")).isEqualTo(CameraTransport.JPEG);
        assertThat(CameraTransport.resolve("ward_aa", "ward_a")).isEqualTo(CameraTransport.JPEG);
    }
}
