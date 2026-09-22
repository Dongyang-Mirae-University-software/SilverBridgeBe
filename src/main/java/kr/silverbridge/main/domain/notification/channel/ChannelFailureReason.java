package kr.silverbridge.main.domain.notification.channel;

/**
 * 채널 발송 실패 사유. 관리자 알림 이력의 "전송 실패 사유"로 그대로 노출된다.
 *
 * <p><b>고정값만 둔다</b> - 예외 메시지 원문에는 토큰·전화번호가 섞일 수 있어 저장하지 않는다.
 * 여기 없는 사유(예: "기기가 응답하지 않음")는 서버가 알 수 없는 정보다. FCM·Solapi가 돌려주는 것은
 * "접수했다/거부했다"까지이고 기기 표시·통신사 도달 여부는 알려주지 않는다.</p>
 */
public enum ChannelFailureReason {

    /** 푸시 토큰이 하나도 없다 - 앱에 로그인한 적이 없거나 알림 권한을 주지 않았다. */
    NO_DEVICE("등록된 기기 없음"),
    /** 모든 토큰이 만료·무효로 거부됐다(UNREGISTERED·INVALID_ARGUMENT) - 앱 삭제·재설치. 토큰은 이때 삭제된다. */
    ALL_TOKENS_EXPIRED("모든 기기의 알림 등록이 만료됨"),
    /** FCM 호출 예외, 또는 만료가 아닌 사유로 전 토큰이 실패했다. */
    PUSH_SERVER_ERROR("푸시 서버 오류"),
    /** 문자·알림톡 수신 번호가 없다. */
    NO_PHONE("등록된 전화번호 없음"),
    /** 발송사(Solapi)가 접수를 거부했다. */
    PROVIDER_REJECTED("발송사가 접수를 거부함"),
    /** 발송사(Solapi) 통신 오류·빈 응답. */
    PROVIDER_ERROR("발송 서버 오류"),
    /** 채널 구현체가 예상하지 못한 예외를 던졌다(디스패처가 격리). */
    UNEXPECTED_ERROR("처리 중 오류");

    private final String label;

    ChannelFailureReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
