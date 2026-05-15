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
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),

    // 사용자
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "비밀번호를 5회 이상 틀렸습니다. 30분 후 다시 시도해주세요."),
    CANNOT_MODIFY_ADMIN(HttpStatus.FORBIDDEN, "관리자 계정은 변경하거나 삭제할 수 없습니다."),
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "역할은 피보호자 또는 보호자만 선택할 수 있습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 전화번호입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다."),
    // 로그인 응답 통합용 — 가입 안 된 이메일/비밀번호 불일치 모두 동일 메시지로 enumeration 차단
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.FORBIDDEN, "사용이 제한된 계정입니다. 고객센터에 문의해주세요."),
    SOCIAL_USER_NO_PASSWORD(HttpStatus.BAD_REQUEST, "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다."),
    SAME_AS_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호는 사용할 수 없습니다."),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 상태값입니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료되었습니다. 다시 로그인해주세요."),
    INVALID_VERIFY_CODE(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
    EXPIRED_VERIFY_CODE(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 요청해주세요."),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 인증된 이메일입니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "비밀번호 재설정 링크가 만료되었거나 유효하지 않습니다."),
    // SMS 인증
    SMS_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "인증번호 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),
    INVALID_SMS_CODE(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
    EXPIRED_SMS_CODE(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 인증번호를 다시 요청해주세요."),
    SMS_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "전화번호 인증을 먼저 완료해주세요."),
    SMS_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "인증번호를 5회 이상 잘못 입력했습니다. 인증번호를 다시 요청해주세요."),
    SMS_SEND_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "1분 후에 다시 요청할 수 있습니다."),

    // 연결 관계
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "연결 관계를 찾을 수 없습니다."),
    CONNECTION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 연결되어 있거나 요청 중인 관계입니다."),
    CONNECTION_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "활성화된 연결 관계가 아닙니다."),
    CONNECTION_NOT_PENDING(HttpStatus.BAD_REQUEST, "수락 대기 중인 연결 관계가 아닙니다."),
    INVALID_CONNECTION_ROLE(HttpStatus.BAD_REQUEST, "보호자와 피보호자 역할이 맞지 않습니다."),
    CONNECTION_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "해당 연결에 대한 권한이 없습니다."),
    CANNOT_CONNECT_SELF(HttpStatus.BAD_REQUEST, "자기 자신과 연결할 수 없습니다."),

    // 게임
    GAME_RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "게임 결과를 찾을 수 없습니다."),

    // 공지
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다."),
    ANNOUNCEMENT_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "임시저장된 공지를 찾을 수 없습니다."),

    // 카카오 OAuth
    KAKAO_INVALID_CODE(HttpStatus.UNAUTHORIZED, "카카오 로그인 시간이 초과되었습니다. 다시 시도해주세요."),
    KAKAO_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "카카오 로그인에 필요한 정보 제공에 동의해주세요."),
    KAKAO_DORMANT_ACCOUNT(HttpStatus.FORBIDDEN, "휴면 또는 존재하지 않는 카카오 계정입니다."),
    KAKAO_AUTH_ERROR(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다. 다시 시도해주세요."),
    KAKAO_SESSION_EXPIRED(HttpStatus.BAD_REQUEST, "카카오 로그인 세션이 만료되었습니다. 카카오 로그인을 다시 시도해주세요."),

    // 파일 서버
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 업로드에 실패했습니다. 잠시 후 다시 시도해주세요."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일 크기는 5MB를 초과할 수 없습니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "이미지 파일(JPG, PNG, WebP, GIF)만 업로드할 수 있습니다."),

    // 요청 제한
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // HTTP 요청 형식 오류
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다. JSON 형식으로 요청해주세요."),
    API_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 API를 찾을 수 없습니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "파일 크기는 5MB를 초과할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
