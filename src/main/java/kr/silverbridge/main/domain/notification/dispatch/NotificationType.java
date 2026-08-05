package kr.silverbridge.main.domain.notification.dispatch;

/**
 * 디스패처를 경유하는 알림 종류와 <b>채널 정책</b>({@link Policy}).
 *
 * <p>정책이 발송 대상 채널을 정한다 — 사용자 설정을 따를지, 무시하고 강제 발송할지, 둘을 섞을지.</p>
 *
 * <p>참고: SMS 인증번호(가입·비밀번호 재설정)는 애초에 디스패처를 경유하지 않고 동기 발송되므로,
 * "설정으로 끌 수 없음"이 구조적으로 보장된다 — 여기 등록할 필요가 없다.</p>
 */
public enum NotificationType {

    CONNECTION_REQUEST(Policy.SETTINGS_ONLY),
    CONNECTION_ACCEPTED(Policy.SETTINGS_ONLY),
    CONNECTION_REFUSED(Policy.SETTINGS_ONLY),
    CONNECTION_DISCONNECTED(Policy.SETTINGS_ONLY),

    // 문의 답변 완료 → 작성자(보호자)에게 알림. 긴급하지 않으므로 사용자 설정을 따른다.
    INQUIRY_ANSWERED(Policy.SETTINGS_ONLY),

    /**
     * 등록 보호자 탈퇴로 복약 일정이 중지됨 → <b>남은 보호자</b>에게 알림.
     *
     * <p>피보호자는 스스로 약을 등록할 수 없으므로, 남은 보호자가 재등록하지 않으면 그 약은 화면에서
     * 사라진 채로 남는다. 조치할 수 있는 사람에게만 보낸다(피보호자 본인에게는 보내지 않는다).
     * 긴급 상황이 아니라 사용자 설정을 따른다.</p>
     */
    MEDICATION_STOPPED(Policy.SETTINGS_ONLY),

    /**
     * 복용 시각 알림 → <b>피보호자 본인</b>에게. 스케줄러가 보낸다(2차).
     *
     * <p>채널은 <b>FCM과 문자뿐</b>이다. {@code SETTINGS_ONLY}라 사용자가 켠 채널로만 나가고,
     * 알림톡은 {@code notification.alimtalk.templates}에 <b>매핑을 두지 않아</b> 조용히 스킵된다.
     * ⚠️ 복약 알림은 매일 반복되는 전형적인 <b>다발성 메시지</b>라, 알림톡으로 보내려면 "반복 수신에
     * 동의했음"을 고정 문구로 고지한 별도 템플릿 승인이 필요하다(이상감지 2차 반려 사유).
     * 그 전에 이 타입에 템플릿을 매핑하면 승인 문구와 다른 발송이 되어 채널 제재 대상이 된다.</p>
     *
     * <p>강제 발송이 아닌 이유 — 복약은 생명이 걸린 즉시 대응(SOS·화재)과 달리 매일 반복되는 일상이라,
     * 끄고 싶은 사용자의 선택을 뒤집을 근거가 없다.</p>
     */
    MEDICATION_REMINDER(Policy.SETTINGS_ONLY),

    // 피보호자 긴급 SOS. 생명 관련이라 설정을 무시하고 강제 발송하며, 푸시 미전달 시 SMS로 폴백한다.
    WARD_SOS(Policy.FORCED_PUSH_WITH_SMS_FALLBACK),

    // 이상감지(화재·연기) — 보호자 수신분. FCM은 끌 수 없고(고정), SMS·알림톡은 사용자가 켠 경우에만 추가 발송한다.
    ANOMALY_DETECTED(Policy.FORCED_PUSH_PLUS_SETTINGS),

    /**
     * 이상감지 — <b>피보호자 본인</b> 수신분(대피 안내 문구). 채널 정책은 보호자분과 같지만 타입을 분리한다.
     *
     * <p>이유는 <b>알림톡</b>이다: 승인된 이상감지 템플릿은 보호자용 문구("회원님께서 보호자로 등록하시고 직접
     * 신청하신… 대상: OOO님")라 본인에게 보내면 사실과 어긋난다 — 승인 문구와 다른 용도의 발송은 카카오 채널
     * 제재 사유다. 이 타입에는 {@code notification.alimtalk.templates} 매핑을 <b>두지 않으므로</b> 알림톡 채널이
     * 조용히 스킵되고, FCM·WebSocket·SMS는 그대로 나간다(화재 시 본인 대피 안내는 유지 — 설계 D-1).</p>
     */
    ANOMALY_DETECTED_SELF(Policy.FORCED_PUSH_PLUS_SETTINGS);

    /** 알림 종류별 채널 결정 규칙. */
    public enum Policy {
        /** 사용자 설정의 활성 채널로만 발송. */
        SETTINGS_ONLY,
        /** 설정 무시하고 FCM 강제 발송, <b>전달 실패 시에만</b> SMS 폴백(WARD_SOS 전용). */
        FORCED_PUSH_WITH_SMS_FALLBACK,
        /**
         * FCM은 설정과 무관하게 항상 발송 + 나머지 채널(SMS·알림톡·이메일)은 사용자가 켠 것만 추가 발송.
         *
         * <p>SMS 폴백은 <b>하지 않는다</b> — 문자는 사용자가 선택(과금·수신 동의)하는 채널이라, 푸시가
         * 실패했다고 문자를 밀어 넣으면 그 선택을 무시하게 된다. 미전달은 로그로만 드러낸다(D-2).</p>
         */
        FORCED_PUSH_PLUS_SETTINGS
    }

    private final Policy policy;

    NotificationType(Policy policy) {
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }
}
