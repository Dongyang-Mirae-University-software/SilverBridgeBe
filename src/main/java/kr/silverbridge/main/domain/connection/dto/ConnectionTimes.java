package kr.silverbridge.main.domain.connection.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 연결 응답의 시각을 한국 시간(+09:00) 표기로 맞춘다.
 *
 * <p>DB(timestamptz)에서 읽은 값은 UTC 표기(Z)로 나가서, 시각 글자의 앞 10자리를 날짜로 쓰는 화면이
 * 한국 새벽(00:00~09:00)의 요청을 전날로 보였다(최종 QA CONN-A12, 2026-11-15). 같은 순간을 표기만 바꾸므로
 * {@code new Date(value)} 로 읽는 쪽 결과는 그대로다.</p>
 */
final class ConnectionTimes {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ConnectionTimes() {
    }

    static OffsetDateTime toKst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
