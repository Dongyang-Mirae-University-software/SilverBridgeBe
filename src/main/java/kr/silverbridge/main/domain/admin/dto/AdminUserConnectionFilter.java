package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원관리 목록의 연결 상태 필터.
 *
 * <p>한 회원이 ACTIVE와 PENDING을 동시에 가질 수 있으므로 <b>우선순위로 하나를 고른다</b> -
 * ACTIVE가 하나라도 있으면 CONNECTED, 없고 PENDING만 있으면 PENDING, 둘 다 없으면 NONE.
 * 표시({@link AdminUserConnectionState})와 필터가 같은 규칙을 써야 "연결됨으로 걸렀는데 수락 대기로 보인다"가 없다.</p>
 *
 * <p>관리자 계정은 연결 축이 없어 NONE으로 분류된다 - 연결 필터를 걸면 목록에서 빠진다.</p>
 */
@Schema(description = "연결 상태 필터 (생략 시 ALL)")
public enum AdminUserConnectionFilter {

    ALL,
    CONNECTED,
    PENDING,
    NONE;

    /** 리포지토리 CASE 식과 짝을 이루는 코드. ALL이면 null(조건 무시). */
    public Integer toCode() {
        return switch (this) {
            case ALL -> null;
            case CONNECTED -> 1;
            case PENDING -> 2;
            case NONE -> 3;
        };
    }

    public static AdminUserConnectionFilter orDefault(AdminUserConnectionFilter filter) {
        return filter == null ? ALL : filter;
    }
}
