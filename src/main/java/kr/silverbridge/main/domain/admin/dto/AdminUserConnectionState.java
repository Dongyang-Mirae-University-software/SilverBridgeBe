package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원 한 명의 연결 상태 표시값. 필터({@link AdminUserConnectionFilter})와 같은 우선순위 규칙으로 계산한다.
 *
 * <p>관리자 계정에는 이 값을 채우지 않는다(null) - 연결이 0건인 것이 아니라 <b>연결이라는 축 자체가 없는</b>
 * 계정이라, NONE("미연결")으로 표시하면 "연결이 끊긴 회원"으로 잘못 읽힌다.</p>
 */
@Schema(description = "연결 상태 (관리자 계정은 null)")
public enum AdminUserConnectionState {
    CONNECTED,
    PENDING,
    NONE
}
