package kr.gosky.sso.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 사용자
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    INVALID_VERIFY_CODE(HttpStatus.BAD_REQUEST, "인증코드가 올바르지 않습니다."),
    EXPIRED_VERIFY_CODE(HttpStatus.BAD_REQUEST, "만료된 인증코드입니다."),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 이메일입니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 토큰입니다."),

    // SSO 클라이언트
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "등록된 서비스를 찾을 수 없습니다."),
    CLIENT_ID_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 클라이언트 ID입니다."),
    INVALID_REDIRECT_URI(HttpStatus.BAD_REQUEST, "redirect_uri는 gosky.kr 도메인만 허용됩니다."),

    // 카카오 OAuth
    KAKAO_INVALID_CODE(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 카카오 인가 코드입니다."),
    KAKAO_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "카카오 사용자 정보 접근 권한이 없습니다. 동의 항목을 확인해주세요."),
    KAKAO_DORMANT_ACCOUNT(HttpStatus.FORBIDDEN, "휴면 또는 존재하지 않는 카카오계정입니다."),
    KAKAO_AUTH_ERROR(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
