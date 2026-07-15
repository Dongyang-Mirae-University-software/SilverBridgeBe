package kr.silverbridge.main.domain.camera.event;

/**
 * 카메라가 등록(또는 같은 기기로 재등록)됐을 때 발행되는 이벤트.
 *
 * <p>이상감지 구독자({@code AiLiveStreamSubscriber})가 이 신호를 받아 AI에 세션 목록을 다시 요청한다.
 * AI는 세션 <b>생성·종료 시점에만</b> 목록을 broadcast하므로, "이미 스트리밍 중인 세션을 나중에 카메라로 등록"하는
 * 순서에서는 목록 broadcast가 이미 지나가 있어 <b>재요청 없이는 영원히 구독되지 않는다</b>(감지·알림이 조용히 0건).</p>
 *
 * @param wardId    소유 피보호자 ID
 * @param sessionId 이 카메라의 SessionID (AI 세션 매칭 키)
 */
public record CameraRegisteredEvent(String wardId, String sessionId) {}
