package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 디바이스 관리 목록의 상태 필터.
 *
 * <p>응답 값({@link AdminCameraLiveStatus})을 그대로 받지 않는다 - 응답 enum에 값이 늘면 필터도 조용히 넓어지고,
 * "전체"는 응답 값이 아니다. {@code AdminUserStatusFilter}·{@code WardListFilter}와 같은 판단이다.</p>
 */
@Schema(description = "카메라 상태 필터 (생략 시 ALL)")
public enum AdminCameraStatusFilter {

    ALL,
    LIVE,
    OFFLINE;

    public static AdminCameraStatusFilter orDefault(AdminCameraStatusFilter filter) {
        return filter == null ? ALL : filter;
    }

    /** 이 필터를 통과하는가. ALL이 아닌 필터는 상태를 알 때만 부른다(모르면 호출부가 503). */
    public boolean matches(AdminCameraLiveStatus status) {
        return switch (this) {
            case ALL -> true;
            case LIVE -> status == AdminCameraLiveStatus.LIVE;
            case OFFLINE -> status == AdminCameraLiveStatus.OFFLINE;
        };
    }
}
