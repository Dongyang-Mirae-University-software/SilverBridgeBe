package kr.silverbridge.main.domain.anomaly.entity;

/**
 * 보호자가 상황에 대해 낸 판단.
 *
 * <p>"모르겠다"를 값으로 두지 않는다 - 판단하지 못한 상태는 <b>응답이 없는 것</b>({@code PENDING})으로
 * 충분히 표현되고, 별도 값을 만들면 "전원 일치" 집계에서 그것이 무엇과 일치하는지 정의할 수 없다.</p>
 */
public enum AnomalyVerdict {

    /** 실제 위험이었다. */
    REAL,

    /** 오탐이었다(불이 나지 않았다). */
    FALSE_ALARM
}
