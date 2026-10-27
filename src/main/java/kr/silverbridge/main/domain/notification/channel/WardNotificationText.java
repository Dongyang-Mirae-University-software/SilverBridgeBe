package kr.silverbridge.main.domain.notification.channel;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * 피보호자(본인)가 받는 알림 문구의 공통 규칙(2026-10-27).
 *
 * <p>어르신이 읽는 문자라 제목은 앱 이름 하나({@link #TITLE})로 두고, 문자 채널이 붙이는 {@code [제목] 본문} 한 줄이
 * <b>단문 한도(EUC-KR 90바이트) 안</b>에 들어가야 한다. 약 이름·개수·용량·숫자 시각·괄호는 넣지 않고, 한 문자에 행동
 * 하나만 담는다. 푸시도 같은 제목·본문을 쓴다. 보호자 문구는 이 규칙을 따르지 않는다(대상 아님).</p>
 */
public final class WardNotificationText {

    /** 피보호자 알림 제목. 문자에서는 {@code [실버브릿지]} 접두어가 된다. */
    public static final String TITLE = "실버브릿지";

    /** 단문(SMS) 한도. 넘으면 발송사가 장문(LMS)으로 바꾼다. */
    public static final int SINGLE_SMS_MAX_BYTES = 90;

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private WardNotificationText() {
    }

    /** 문자 채널이 실제로 보내는 한 줄({@link SmsNotificationChannel}과 같은 조합). */
    public static String smsLine(String body) {
        return "[" + TITLE + "] " + body;
    }

    /** 본문을 문자로 보냈을 때 단문 한도 안인가. */
    public static boolean fitsSingleSms(String body) {
        return eucKrBytes(smsLine(body)) <= SINGLE_SMS_MAX_BYTES;
    }

    /** EUC-KR 바이트 수. EUC-KR로 바꿀 수 없는 글자(이모지·확장 한자 등)는 2바이트로 센다. */
    public static int eucKrBytes(String text) {
        CharsetEncoder encoder = EUC_KR.newEncoder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            bytes += encoder.canEncode(ch) ? ch.getBytes(EUC_KR).length : 2;
            i += Character.charCount(codePoint);
        }
        return bytes;
    }
}
