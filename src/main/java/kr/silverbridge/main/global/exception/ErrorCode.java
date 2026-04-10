package kr.silverbridge.main.global.exception;

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
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "역할은 WARD(피보호자) 또는 GUARDIAN(보호자)만 선택할 수 있습니다."),
    PENDING_USER(HttpStatus.FORBIDDEN, "카카오 로그인 후 역할 선택이 필요합니다. /api/auth/kakao/role 을 호출해주세요."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    SOCIAL_USER_NO_PASSWORD(HttpStatus.BAD_REQUEST, "카카오로 로그인한 사용자는 비밀번호 재설정 기능을 사용할 수 없습니다."),
    SAME_AS_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호는 사용할 수 없습니다."),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 상태값입니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    INVALID_VERIFY_CODE(HttpStatus.BAD_REQUEST, "인증코드가 올바르지 않습니다."),
    EXPIRED_VERIFY_CODE(HttpStatus.BAD_REQUEST, "만료된 인증코드입니다."),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 이메일입니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 토큰입니다."),
    PASSWORD_RECENTLY_USED(HttpStatus.BAD_REQUEST, "최근에 사용한 비밀번호는 다시 사용할 수 없습니다."),

    // SMS 인증
    SMS_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "SMS 발송에 실패했습니다."),
    INVALID_SMS_CODE(HttpStatus.BAD_REQUEST, "SMS 인증코드가 올바르지 않습니다."),
    EXPIRED_SMS_CODE(HttpStatus.BAD_REQUEST, "만료된 SMS 인증코드입니다."),
    SMS_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "SMS 인증이 완료되지 않았습니다."),
    SMS_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "인증코드를 5회 이상 틀렸습니다. 인증코드를 다시 요청해주세요."),
    SMS_SEND_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "SMS는 1분에 1회만 요청할 수 있습니다."),

    // 카카오 OAuth
    KAKAO_INVALID_CODE(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 카카오 인가 코드입니다."),
    KAKAO_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "카카오 사용자 정보 접근 권한이 없습니다. 동의 항목을 확인해주세요."),
    KAKAO_DORMANT_ACCOUNT(HttpStatus.FORBIDDEN, "휴면 또는 존재하지 않는 카카오계정입니다."),
    KAKAO_AUTH_ERROR(HttpStatus.UNAUTHORIZED, "카카오 인증에 실패했습니다."),
    KAKAO_SESSION_EXPIRED(HttpStatus.BAD_REQUEST, "카카오 로그인 세션이 만료되었습니다. 카카오 로그인을 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;
}
