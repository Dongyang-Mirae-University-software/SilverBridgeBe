package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;

/**
 * 회원 정보 수정 요청. 모든 필드가 <b>선택</b>이며 {@code null}은 "변경하지 않음"이다
 * (프로젝트 공통 규약 - 복약 설정·이상감지 정정과 같다).
 *
 * <p><b>이메일·전화번호는 없다.</b> 전화번호는 본인 경로에서 SMS 인증 nonce 소비가 필수라(H-5)
 * 관리자 경로를 열면 그 인증을 통째로 우회하고, 잘못 넣으면 SOS·복약 문자가 남의 번호로 간다.
 * 이메일은 본인조차 바꿀 수 없는 값이라, 관리자에게 열면 시스템에서 유일한 로그인 ID 변경 경로가 된다.</p>
 *
 * @param status       이용 중(ACTIVE)·이용 제한(RESTRICTED)만 지정할 수 있다. INACTIVE는 400 -
 *                     탈퇴는 상태 변경이 아니라 삭제(강제 탈퇴)이며, 여기서 INACTIVE로 바꾸면 스윕이 계정을 지운다.
 *                     <b>피보호자(WARD)는 이용 제한할 수 없다</b>(400) - 로그인이 막히면 SOS를 보낼 수 없게 된다
 * @param statusReason 이용 제한 사유. 정지는 "본인 확인이 될 때까지의 임시 조치"라, 왜 잠갔는지가 없으면
 *                     해제 판단도 사후 소명도 할 수 없다. 해제하면 지워진다
 */
@Schema(description = "회원 정보 수정 요청 (모든 필드 선택, null=변경 안 함)")
public record AdminUserUpdateRequest(

        @Schema(description = "이름 (최대 20자). null이면 변경하지 않음", nullable = true, example = "홍길동")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
        String name,

        @Schema(description = "역할. WARD·GUARDIAN만 지정할 수 있고 null이면 변경하지 않음",
                nullable = true, example = "GUARDIAN", allowableValues = {"WARD", "GUARDIAN"})
        Role role,

        @Schema(description = "계정 상태. ACTIVE·RESTRICTED만 지정할 수 있고 null이면 변경하지 않음",
                nullable = true, example = "RESTRICTED", allowableValues = {"ACTIVE", "RESTRICTED"})
        Status status,

        @Schema(description = "이용 제한 사유 (최대 200자). RESTRICTED로 바꿀 때만 저장되고, "
                + "ACTIVE로 되돌리면 지워집니다. 상태를 바꾸지 않으면 무시됩니다.",
                nullable = true, example = "본인 신고 - 계정 탈취 의심, 확인 중")
        @Size(max = 200, message = "이용 제한 사유는 200자 이하여야 합니다.")
        String statusReason
) {}
