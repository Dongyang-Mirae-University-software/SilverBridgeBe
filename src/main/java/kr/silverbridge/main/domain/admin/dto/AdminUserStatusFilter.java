package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.enums.Status;

/**
 * 회원관리 목록의 계정 상태 필터.
 *
 * <p><b>{@link Status}를 그대로 받지 않는 이유</b> - 전체 상태 enum을 파라미터로 열면 {@code status=INACTIVE}가
 * 유효한 값이라 400이 아니라 <b>빈 배열</b>로 응답되고, 호출자는 "탈퇴 회원이 0명"이라고 정반대로 읽는다.
 * 실제로는 탈퇴 행이 목록의 모집단에서 아예 빠져 있다(purge 대기 중인 임시 상태라 관리자가 할 일이 없다).
 * 피보호자 목록의 {@code WardListFilter}(2026-09-07)와 같은 판단이다.</p>
 */
@Schema(description = "계정 상태 필터 (생략 시 ALL)")
public enum AdminUserStatusFilter {

    ALL,
    ACTIVE,
    RESTRICTED;

    /** 리포지토리에 넘길 상태값. ALL이면 null(조건 무시). */
    public Status toStatus() {
        return this == ALL ? null : Status.valueOf(name());
    }

    public static AdminUserStatusFilter orDefault(AdminUserStatusFilter filter) {
        return filter == null ? ALL : filter;
    }
}
