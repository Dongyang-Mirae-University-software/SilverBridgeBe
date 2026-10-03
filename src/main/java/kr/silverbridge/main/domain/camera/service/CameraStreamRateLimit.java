package kr.silverbridge.main.domain.camera.service;

/**
 * 보호자 실시간 카메라 API의 속도 제한(보호자 ID 기준, 분·시간 이중 윈도우 - 2026-10-04 점검 L-4).
 *
 * <p>호출마다 DB 조회와 AI 서버 호출이 붙어(스냅샷은 최대 5MB를 메모리에 올린다) 반복 호출이 그대로 AI 부하로
 * 증폭된다. 상한은 정상 화면 사용의 여러 배로 잡았다 - 목록은 FE가 15초마다(분당 4회) 부르고, 티켓은 영상을 다시
 * 열 때(30분 상한 재연결·카메라 전환)만 받는다. 넘으면 429 + {@code Retry-After}이고, Redis 장애 시에는 통과한다
 * ({@code RateLimitService} fail-open - 보조 방어라 시청을 막지 않는다).</p>
 */
enum CameraStreamRateLimit {

    LIVE("camera-live", 30, 600),
    STATUS("camera-status", 30, 600),
    FRAME("camera-frame", 60, 1200),
    TICKET("camera-ticket", 20, 300);

    final String endpoint;
    final int perMinute;
    final int perHour;

    CameraStreamRateLimit(String endpoint, int perMinute, int perHour) {
        this.endpoint = endpoint;
        this.perMinute = perMinute;
        this.perHour = perHour;
    }
}
