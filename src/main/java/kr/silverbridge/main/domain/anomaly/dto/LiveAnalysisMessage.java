package kr.silverbridge.main.domain.anomaly.dto;

/**
 * STOMP {@code /topic/{guardianId}/camera-analysis} 페이로드 - 카메라 실시간 분석 상태(화면 표시용).
 *
 * <p>{@code anomaly-detected}(판정을 거친 화재 알림)와 다른 이벤트다. 판정·이력·쿨다운을 거치지 않은 AI 상태 그대로라
 * FE는 토스트·알림음을 띄우지 않는다. 시각은 KST ISO 문자열이다(WS 메시지 변환기의 날짜 설정에 기대지 않는다).</p>
 *
 * @param status       송출 상태(running·disconnected·offline). <b>null = AI 연결이 끊겨 확인 불가</b>(꺼짐이 아니다)
 * @param detectedType 감지 종류(FIRE·NORMAL·UNKNOWN 등). 분석을 받은 적 없으면 null
 */
public record LiveAnalysisMessage(
        String sessionId,
        String wardId,
        String status,
        String detectedType,
        String detectedTypeLabel,
        Double confidence,
        Boolean danger,
        String analyzedAt
) {}
