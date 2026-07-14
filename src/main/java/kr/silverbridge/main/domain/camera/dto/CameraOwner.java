package kr.silverbridge.main.domain.camera.dto;

/**
 * AI 신호의 {@code sessionId}로 찾은 카메라의 소유자·위치 (anomaly 도메인 협력용).
 *
 * <p>엔티티를 도메인 밖으로 넘기지 않기 위한 최소 뷰다. 이상감지 이력에는 {@code wardId}가,
 * 알림 문구("홍길동님 댁 <b>거실</b>에서 …")에는 {@code label}이 필요하다.</p>
 *
 * @param wardId 카메라 소유 피보호자 ID
 * @param label  설치 위치(방 이름 — 거실·안방 등)
 */
public record CameraOwner(String wardId, String label) {}
