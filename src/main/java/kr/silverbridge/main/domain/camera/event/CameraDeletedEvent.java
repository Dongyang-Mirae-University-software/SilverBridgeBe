package kr.silverbridge.main.domain.camera.event;

import java.util.List;

/**
 * 카메라가 삭제됐을 때 발행된다(2026-10-04) - 피보호자 본인 삭제와 관리자 역할 변경 일괄 삭제 두 경로.
 *
 * <p>이상감지 클립 정리용이다(anomaly 도메인이 AFTER_COMMIT으로 받는다). camera가 anomaly를 직접 부르지 않게 이벤트로 둔다.
 * 회원 탈퇴로 카메라가 CASCADE 삭제될 때는 발행되지 않는다 - 그쪽은 {@code UserWithdrawnEvent}가 맡는다.</p>
 *
 * @param wardId     카메라 소유 피보호자
 * @param sessionIds 삭제된 카메라들의 sessionId(삭제 전에 모은 값)
 */
public record CameraDeletedEvent(String wardId, List<String> sessionIds) {}
