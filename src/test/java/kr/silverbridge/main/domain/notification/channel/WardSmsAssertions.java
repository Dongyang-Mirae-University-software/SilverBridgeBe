package kr.silverbridge.main.domain.notification.channel;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 피보호자 문구 규칙(2026-10-27) 공용 단언. 각 도메인 테스트가 실제 코드가 만든 본문을 넘겨 확인한다.
 * 규칙: 문자 한 줄({@code [실버브릿지] 본문})이 EUC-KR 90바이트 이하 / 숫자 시각·괄호·개수 표기 없음 / 제목이 본문에 다시 나오지 않음.
 */
public final class WardSmsAssertions {

    private static final Pattern CLOCK_TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern COUNT = Pattern.compile("\\d+\\s*(개|정|알)");

    private WardSmsAssertions() {
    }

    public static void assertPlainWardSms(String body) {
        String line = WardNotificationText.smsLine(body);
        assertThat(WardNotificationText.eucKrBytes(line))
                .as("단문 한도(90바이트) 이내: %s", line)
                .isLessThanOrEqualTo(WardNotificationText.SINGLE_SMS_MAX_BYTES);
        assertThat(body).as("숫자 시각 없음: %s", body).doesNotContainPattern(CLOCK_TIME);
        assertThat(body).as("개수·용량 표기 없음: %s", body).doesNotContainPattern(COUNT);
        assertThat(body).as("괄호 없음: %s", body).doesNotContain("(", ")", "[", "]");
        assertThat(body).as("제목 중복 없음: %s", body).doesNotContain(WardNotificationText.TITLE);
    }
}
