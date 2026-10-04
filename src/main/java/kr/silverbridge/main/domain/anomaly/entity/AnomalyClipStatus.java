package kr.silverbridge.main.domain.anomaly.entity;

/**
 * 이상감지 클립의 공개 상태.
 *
 * <p>값을 더하면 {@code chk_anomaly_clip_status} 재정의 마이그레이션을 함께 넣을 것
 * ({@code EnumCheckConstraintIntegrationTest}가 막는다).</p>
 */
public enum AnomalyClipStatus {

    /** 열람 가능(보호자·피보호자 열람 조건은 별도). */
    VISIBLE,

    /**
     * 상황이 오탐(FALSE_ALARM)으로 확정돼 비공개. 24시간 뒤 청소 스케줄러가 물리 삭제하고, 그 안에 판정이
     * 번복되면(REAL·CONFLICTED·PENDING) {@link #VISIBLE}로 돌아온다.
     */
    HIDDEN
}
