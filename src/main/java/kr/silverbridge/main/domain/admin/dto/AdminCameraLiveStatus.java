package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 디바이스 관리의 카메라 상태. 알 수 없을 때는 이 enum의 값이 아니라 {@code null}이다
 * ("모르는 값을 0·끊김으로 채우지 않는다" - 대시보드 2026-09-02와 같은 규칙).
 */
@Schema(description = "LIVE = AI가 영상을 받고 있고 백엔드가 감시(구독) 중 / OFFLINE = AI 목록에 없거나 감시하지 못함")
public enum AdminCameraLiveStatus {
    LIVE,
    OFFLINE
}
