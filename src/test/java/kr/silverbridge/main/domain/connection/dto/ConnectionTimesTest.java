package kr.silverbridge.main.domain.connection.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 연결 응답 시각의 한국 시간 표기 (최종 QA CONN-A12, 2026-11-15) */
class ConnectionTimesTest {

    @Test
    @DisplayName("UTC 로 읽힌 한국 새벽 시각은 같은 순간의 +09:00 표기가 되어, 앞 10자리가 한국 날짜다")
    void 한국_새벽_요청일() {
        // 한국 2026-09-30 00:30 = UTC 2026-09-29 15:30
        OffsetDateTime utc = OffsetDateTime.of(2026, 9, 29, 15, 30, 0, 0, ZoneOffset.UTC);

        OffsetDateTime kst = ConnectionTimes.toKst(utc);

        assertThat(kst.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(kst.toInstant()).isEqualTo(utc.toInstant());
        assertThat(kst.toString()).startsWith("2026-09-30");
        assertThat(ConnectionTimes.toKst(null)).isNull();
    }
}
