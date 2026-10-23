package kr.silverbridge.main.domain.admin.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 디바이스 관리 응답에 싣는 칸을 고정한다(2026-10-23).
 *
 * <p>송출 기기 토큰(deviceId)은 재등록 키라 싣지 않고, 영상·정지 화면·클립은 관리자 열람 금지라 주소도 싣지 않는다
 * ({@code AdminAnomalyClipExposureTest}와 같은 취지). 상세 주소도 목록에 필요 없다. 칸을 더하려면 이 목록과
 * 정책을 함께 바꿀 것.</p>
 */
class AdminCameraItemExposureTest {

    @Test
    @DisplayName("목록 항목 칸은 허용 목록뿐 - deviceId·영상·클립·상세 주소 칸이 없다")
    void itemFields() {
        assertThat(names(AdminCameraItem.class)).containsExactly(
                "cameraId", "sessionId", "room", "wardId", "wardName", "wardAddress",
                "isActive", "registeredAt", "liveStatus");
    }

    @Test
    @DisplayName("요약 칸에도 기기·영상 관련 칸이 없다")
    void summaryFields() {
        assertThat(names(AdminCameraSummaryResponse.class))
                .noneMatch(name -> name.toLowerCase().matches(".*(device|stream|frame|clip|url|snapshot).*"));
    }

    private static List<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
