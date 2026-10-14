package kr.silverbridge.main.domain.camera.service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 카메라 송출 방식 값(응답 {@code transport}). 의미는 <b>송출</b> 방식이다 - 시청(MJPEG 중계)은 이 값과 무관하다.
 */
public final class CameraTransport {

    public static final String JPEG = "jpeg";
    public static final String WS_H264 = "ws-h264";

    private CameraTransport() {
    }

    /** 쉼표 목록 설정값을 sessionId 집합으로 바꾼다(공백·빈 항목 제거, null 허용). */
    public static Set<String> parseSessionIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 목록에 든 sessionId만 ws-h264, 그 밖은 jpeg(기본). */
    public static String resolve(String sessionId, String csv) {
        return sessionId != null && parseSessionIds(csv).contains(sessionId) ? WS_H264 : JPEG;
    }
}
