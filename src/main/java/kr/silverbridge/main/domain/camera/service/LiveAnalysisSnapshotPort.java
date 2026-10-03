package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.dto.LiveAnalysisSnapshot;

import java.util.Optional;

/**
 * 카메라별 최근 AI 분석 결과 조회 포트.
 *
 * <p>분석 결과는 이상감지 도메인의 AI WebSocket 수신기가 받는다. 카메라 도메인은 이상감지를 import하지 않고
 * (이상감지가 이미 카메라를 쓰므로 반대 방향은 순환이 된다) 이 인터페이스만 보고, 구현은 이상감지 쪽에 둔다.</p>
 */
public interface LiveAnalysisSnapshotPort {

    /** 받은 적이 없거나 AI 연결이 끊긴 뒤면 빈 값 - 오래된 결과를 현재 상태처럼 보여주지 않는다. */
    Optional<LiveAnalysisSnapshot> findLatest(String sessionId);
}
