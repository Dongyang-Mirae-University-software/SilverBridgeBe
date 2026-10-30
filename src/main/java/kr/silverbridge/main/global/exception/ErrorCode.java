package kr.silverbridge.main.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "파일이 필요합니다."),
    DUPLICATE_VALUE(HttpStatus.CONFLICT, "이미 사용 중이거나 중복된 값입니다. 입력값을 확인해주세요."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청이 먼저 처리되었습니다. 새로고침 후 다시 시도해주세요."),

    // 사용자
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    // 비밀번호 재설정 — 미가입 이메일 안내 (시니어 친화 UX, 2026-05-23). SMS 미일치는 USER_NOT_FOUND 재사용
    EMAIL_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 이메일로 가입된 계정이 없습니다."),
    // 분 수는 auth.login.lock-ttl-minutes 기본값(10)과 맞춘다 - 설정을 바꾸면 이 문구도 함께 바꿀 것
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "비밀번호를 5회 이상 틀렸습니다. 10분 후 다시 시도해주세요."),
    // 비밀번호 변경·탈퇴의 현재 비밀번호 확인 잠금(UserService, 30분) - 로그인 잠금과 시간이 달라 코드를 나눴다(2026-10-30)
    PASSWORD_CHECK_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "비밀번호를 5회 이상 틀렸습니다. 30분 후 다시 시도해주세요."),
    CANNOT_MODIFY_ADMIN(HttpStatus.FORBIDDEN, "관리자 계정은 변경하거나 삭제할 수 없습니다."),
    // 관리자 본인 탈퇴 차단(USER-G08, 결정 D4-A) - 관리자는 DB로만 만들어져 마지막 관리자가 사라지면 운영 주체가 없어진다
    ADMIN_CANNOT_WITHDRAW(HttpStatus.FORBIDDEN, "관리자 계정은 탈퇴할 수 없습니다."),
    INVALID_ROLE(HttpStatus.BAD_REQUEST, "역할은 피보호자 또는 보호자만 선택할 수 있습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 전화번호입니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다."),
    // 로그인 응답 통합용 — 가입 안 된 이메일/비밀번호 불일치 모두 동일 메시지로 enumeration 차단
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_USER(HttpStatus.FORBIDDEN, "사용이 제한된 계정입니다. 고객센터에 문의해주세요."),
    SOCIAL_USER_NO_PASSWORD(HttpStatus.BAD_REQUEST, "카카오로 가입한 계정은 비밀번호 재설정을 사용할 수 없습니다."),
    SAME_AS_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호는 사용할 수 없습니다."),
    WITHDRAW_CONFIRMATION_MISMATCH(HttpStatus.BAD_REQUEST, "탈퇴 확인 문구가 일치하지 않습니다. \"탈퇴\"를 정확히 입력해주세요."),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 상태값입니다."),
    WARD_CANNOT_BE_RESTRICTED(HttpStatus.BAD_REQUEST,
            "피보호자 계정은 이용 제한할 수 없습니다. 긴급 도움 요청(SOS)을 보낼 수 없게 됩니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "로그인 정보가 유효하지 않습니다. 다시 로그인해주세요."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료되었습니다. 다시 로그인해주세요."),
    // SMS 인증
    SMS_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "인증번호 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),
    INVALID_SMS_CODE(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
    EXPIRED_SMS_CODE(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 인증번호를 다시 요청해주세요."),
    SMS_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "전화번호 인증을 먼저 완료해주세요."),
    SMS_TOO_MANY_ATTEMPTS(HttpStatus.BAD_REQUEST, "인증번호를 5회 이상 잘못 입력했습니다. 인증번호를 다시 요청해주세요."),

    // 연결 관계
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "연결 관계를 찾을 수 없습니다."),
    CONNECTION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 연결되어 있거나 요청 중인 관계입니다."),
    CONNECTION_NOT_ACTIVE(HttpStatus.CONFLICT, "활성화된 연결 관계가 아닙니다."),
    CONNECTION_NOT_PENDING(HttpStatus.CONFLICT, "수락 대기 중인 연결 관계가 아닙니다."),
    INVALID_CONNECTION_ROLE(HttpStatus.BAD_REQUEST, "보호자와 피보호자 역할이 맞지 않습니다."),
    CONNECTION_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "해당 연결에 대한 권한이 없습니다."),
    CONNECTION_TARGET_NOT_ACTIVE(HttpStatus.BAD_REQUEST,
            "이용 중인 회원만 연결할 수 있습니다. 이용 제한·탈퇴 처리 중인 계정은 연결할 수 없습니다."),
    CANNOT_CONNECT_SELF(HttpStatus.BAD_REQUEST, "자기 자신과 연결할 수 없습니다."),
    // 같은 피보호자에게 요청·취소를 반복해 알림을 계속 보내는 것을 막는다(CONN-G04) - retryAfterSeconds 동봉
    CONNECTION_REQUEST_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS,
            "같은 분께 연결 요청을 여러 번 보냈습니다. 잠시 후 다시 시도해주세요."),

    // 공지
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다."),
    ANNOUNCEMENT_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "임시저장된 공지를 찾을 수 없습니다."),

    // 문의(고객센터)
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다."),
    // 타인 문의 접근 — 무슨 일이 일어났는지 그대로 안내한다(시니어 UX 우선, 2026-07-14 정책)
    INQUIRY_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "본인이 작성한 문의만 볼 수 있습니다."),
    INQUIRY_ALREADY_ANSWERED(HttpStatus.CONFLICT, "이미 답변이 완료된 문의입니다."),

    // 카카오 OAuth
    KAKAO_INVALID_CODE(HttpStatus.UNAUTHORIZED, "카카오 로그인 시간이 초과되었습니다. 다시 시도해주세요."),
    KAKAO_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "카카오 로그인에 필요한 정보 제공에 동의해주세요."),
    KAKAO_DORMANT_ACCOUNT(HttpStatus.FORBIDDEN, "휴면 또는 존재하지 않는 카카오 계정입니다."),
    KAKAO_AUTH_ERROR(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다. 다시 시도해주세요."),
    KAKAO_SESSION_EXPIRED(HttpStatus.BAD_REQUEST, "카카오 로그인 세션이 만료되었습니다. 카카오 로그인을 다시 시도해주세요."),
    // 일반 가입 계정과 같은 이메일로 카카오 가입 시도 (AUTH-G08) - 연동하지 않고 기존 로그인 방법을 안내
    KAKAO_EMAIL_REGISTERED_LOCAL(HttpStatus.CONFLICT, "이미 이메일/비밀번호로 가입된 계정입니다. 기존 로그인 방법을 사용해주세요."),

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

    // 카메라(이상감지)
    CAMERA_NOT_FOUND(HttpStatus.NOT_FOUND, "카메라를 찾을 수 없습니다."),
    // 타인 카메라 접근 — 무슨 일이 일어났는지 그대로 안내한다(시니어 UX 우선, 2026-07-14 정책)
    CAMERA_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "본인이 등록한 카메라만 사용할 수 있습니다."),
    // 보호자 경로용 - 문구는 수신자 기준이라 피보호자용(CAMERA_NOT_AUTHORIZED)과 나눈다(2026-08-06 정책)
    CAMERA_NOT_CONNECTED(HttpStatus.FORBIDDEN, "연결된 피보호자의 카메라만 볼 수 있습니다."),
    // 방은 정해진 목록(CameraRoom)에서만 고른다, 한 방에는 카메라 1대(2026-10-05)
    CAMERA_ROOM_INVALID(HttpStatus.BAD_REQUEST, "선택할 수 없는 방입니다. 목록에서 방을 골라주세요."),
    CAMERA_LABEL_DUPLICATED(HttpStatus.CONFLICT, "같은 방에 이미 등록된 카메라가 있습니다."),
    // 서비스 전체 등록 상한(camera.max-total) - AI 동시 분석 한계를 코드로 보장한다(2026-10-14)
    CAMERA_LIMIT_REACHED(HttpStatus.CONFLICT,
            "현재 서비스에서 등록할 수 있는 카메라를 모두 사용했습니다. 등록된 카메라를 정리한 뒤 다시 시도해주세요."),
    CAMERA_NOT_STREAMING(HttpStatus.NOT_FOUND, "카메라가 지금 영상을 보내고 있지 않습니다."),
    CAMERA_STREAM_TICKET_INVALID(HttpStatus.UNAUTHORIZED, "영상 연결이 만료되었습니다. 다시 시도해주세요."),
    CAMERA_STREAM_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "동시에 볼 수 있는 영상 수를 넘었습니다. 다른 영상을 닫고 다시 시도해주세요."),
    CAMERA_STREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "카메라 영상 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
    // 관리자 디바이스 관리 - AI 연결이 끊겨 상태를 모를 때 정상·오프라인 필터를 빈 목록으로 답하지 않는다(2026-10-23)
    CAMERA_LIVE_STATUS_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE,
            "카메라 연결 상태를 지금 확인할 수 없습니다. 잠시 후 다시 시도해주세요."),

    // SOS 이력 조회 (처리(ACK) 기능 철회로 SOS_EVENT_NOT_FOUND 제거 - 2026-08-26, V39)
    // 연결되지 않은 피보호자의 이력 접근 — 404 위장 대신 그대로 안내한다(2026-07-14 정책)
    SOS_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "연결된 피보호자의 SOS 이력만 볼 수 있습니다."),
    DASHBOARD_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "연결된 피보호자의 대시보드만 볼 수 있습니다."),

    // 복약 알림
    MEDICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "복약 정보를 찾을 수 없습니다."),
    // 연결되지 않은 피보호자의 약 접근(보호자 경로) — 404 위장 대신 그대로 안내한다(2026-07-14 정책)
    MEDICATION_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "연결된 피보호자의 복약 정보만 볼 수 있습니다."),
    // 타인의 약 체크 시도(피보호자 경로) — 위 문구는 "연결된 피보호자"를 전제해 피보호자에게는 뜻이 통하지 않는다.
    // 무슨 일이 일어났는지 그대로 안내한다는 2026-07-14 정책의 취지를 살리려면 수신자 기준의 문구가 필요하다.
    MEDICATION_NOT_OWNED(HttpStatus.FORBIDDEN, "본인의 약만 체크할 수 있습니다."),
    // 약 봉투 OCR (2026-10-22) - AI 오류 문구는 내리지 않고 고정 문구만 쓴다
    MEDICATION_OCR_IMAGE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "사진 크기는 8MB를 넘을 수 없습니다."),
    MEDICATION_OCR_INVALID_IMAGE(HttpStatus.BAD_REQUEST, "JPG, PNG, WebP 사진만 올릴 수 있습니다."),
    MEDICATION_OCR_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "이전 사진을 읽는 중입니다. 잠시 후 다시 시도해주세요."),
    MEDICATION_OCR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "약 봉투 읽기를 지금 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    // 관리자 제휴 병원 관리 - 예약 서비스 중계 (2026-10-26). 예약 서비스 오류 본문은 읽지 않고 상태 코드로만 고른다
    HOSPITAL_NOT_FOUND(HttpStatus.NOT_FOUND, "병원을 찾을 수 없습니다."),
    HOSPITAL_DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "진료과를 찾을 수 없습니다."),
    HOSPITAL_DEPARTMENT_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 진료과입니다."),
    HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST(HttpStatus.BAD_REQUEST, "같은 진료과가 두 번 들어 있습니다."),
    HOSPITAL_INVALID_HOURS(HttpStatus.BAD_REQUEST, "진료 시작 시각은 종료 시각보다 빨라야 합니다."),
    HOSPITAL_INVALID_BREAK(HttpStatus.BAD_REQUEST, "점심시간은 시작과 끝을 함께, 진료 시간 안에서 시작이 끝보다 빠르게 정해주세요."),
    HOSPITAL_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "예약 서비스가 요청을 받아들이지 않았습니다. 입력값을 확인해주세요."),
    HOSPITAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "제휴 병원 관리를 지금 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    HOSPITAL_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "예약 서비스 응답이 늦습니다. 잠시 후 다시 시도해주세요."),
    // 점검 M-1(2026-10-26): 쓰기가 예약 서비스에 닿은 뒤 응답을 확인하지 못한 경우 - 실패로 보이면 다시 눌러 중복이 생긴다
    HOSPITAL_RESULT_UNKNOWN(HttpStatus.GATEWAY_TIMEOUT,
            "예약 서비스 응답을 확인하지 못했습니다. 이미 반영됐을 수 있으니 목록을 새로고침해 확인한 뒤 다시 시도해주세요."),
    HOSPITAL_DUPLICATED(HttpStatus.CONFLICT, "같은 이름과 주소의 병원이 이미 등록되어 있습니다."),
    MEDICATION_OCR_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "약 봉투를 읽는 데 시간이 너무 오래 걸립니다. 잠시 후 다시 시도해주세요."),
    // 약 일괄 등록 (2026-10-22) - 같은 요청 안의 중복은 오입력으로 보고 전체를 거절한다
    MEDICATION_BATCH_DUPLICATE_NAME(HttpStatus.BAD_REQUEST, "같은 이름의 약이 두 번 들어 있습니다."),
    MEDICATION_BATCH_DUPLICATE_TIME(HttpStatus.BAD_REQUEST, "같은 복용 시각이 두 번 들어 있습니다."),
    MEDICATION_INTAKE_BATCH_DUPLICATE(HttpStatus.BAD_REQUEST, "같은 약이 두 번 들어 있습니다."),
    MEDICATION_DELETE_BATCH_DUPLICATE(HttpStatus.BAD_REQUEST, "같은 약이 두 번 들어 있습니다."),

    // 이상감지 판정 (2026-08-31)
    ANOMALY_INCIDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이상감지 기록을 찾을 수 없습니다."),
    // 없는 자원은 404, 남의 자원은 403으로 그대로 안내한다(2026-07-14 정책). 문구는 수신자(보호자) 기준이다
    ANOMALY_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "연결된 피보호자의 이상감지 기록만 볼 수 있습니다."),

    // 이상감지 영상 클립 (2026-10-04). 없음·비공개(오탐 확정)·보관 기간 경과·피보호자의 연결 0건은 모두 404 -
    // 비공개 사유(오탐·연결 상태)를 응답으로 구분해 알리지 않는다
    ANOMALY_CLIP_NOT_FOUND(HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다."),
    // 피보호자 경로용 - 보호자 경로는 ANOMALY_NOT_AUTHORIZED를 쓴다(문구는 수신자 기준, 2026-08-06 정책)
    ANOMALY_CLIP_NOT_OWNED(HttpStatus.FORBIDDEN, "본인 집의 이상감지 영상만 볼 수 있습니다."),

    // AI 챗봇 중계 (2026-10-07). AI 오류 문구는 그대로 내리지 않고 고정 문구만 쓴다
    CHAT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 상담 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."),
    CHAT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 상담 응답이 너무 오래 걸립니다. 잠시 후 다시 시도해주세요."),
    CHAT_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "상담 요청 형식이 올바르지 않습니다."),
    AI_STATS_INVALID_RANGE(HttpStatus.BAD_REQUEST,
            "기간은 YYYY-MM-DD 형식(2000~2999년)으로, 시작일이 종료일보다 늦지 않게, 최대 31일까지 조회할 수 있습니다."),
    AI_STATS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 통계를 지금 불러올 수 없습니다. 잠시 후 다시 시도해주세요."),
    AI_STATS_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 통계 응답이 늦습니다. 잠시 후 다시 시도해주세요."),
    CHAT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 기록을 찾을 수 없습니다."),
    CHAT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "이전 상담 응답을 기다리는 중입니다. 잠시 후 다시 시도해주세요."),
    CHAT_WARD_NOT_CONNECTED(HttpStatus.FORBIDDEN, "연결된 피보호자만 대신 상담·예약할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
