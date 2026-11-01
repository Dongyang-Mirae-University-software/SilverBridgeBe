package kr.silverbridge.main.domain.checkin.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 말벗 enum ↔ DB CHECK 동기화 가드. enum에 값만 더하면 INSERT가 CHECK 위반으로 실패한다 - 값을 더하면 CHECK 재정의 마이그레이션이 필요하다.
 */
class CheckinCheckSyncTest {

    @Test
    @DisplayName("대화 상태·끝난 이유·정서·시각이 CHECK 허용 목록과 같다")
    void enums() throws IOException {
        for (CheckinSessionStatus v : CheckinSessionStatus.values()) {
            assertThat(latestCheck("chk_checkin_session_status")).contains("'" + v.name() + "'");
        }
        for (CheckinEndReason v : CheckinEndReason.values()) {
            assertThat(latestCheck("chk_checkin_session_end_reason")).contains("'" + v.name() + "'");
            assertThat(latestCheck("chk_checkin_result_end_reason")).contains("'" + v.name() + "'");
        }
        for (CheckinEmotion v : CheckinEmotion.values()) {
            assertThat(latestCheck("chk_checkin_result_emotion")).contains("'" + v.name() + "'");
            if (v.selectableBySelf()) {
                assertThat(latestCheck("chk_checkin_result_self_report")).contains("'" + v.name() + "'");
            } else {
                assertThat(latestCheck("chk_checkin_result_self_report")).doesNotContain("'" + v.name() + "'");
            }
        }
        for (CheckinSlot v : CheckinSlot.values()) {
            assertThat(latestCheck("chk_checkin_session_slot")).contains("'" + v.value() + "'");
            assertThat(latestCheck("chk_checkin_result_slot")).contains("'" + v.value() + "'");
            assertThat(latestCheck("chk_checkin_question_slot")).contains("'" + v.value() + "'");
            assertThat(latestCheck("chk_checkin_prompt_log_slot")).contains("'" + v.value() + "'");
            assertThat(latestCheck("chk_checkin_guardian_alert_log_slot")).contains("'" + v.value() + "'");
        }
    }

    @Test
    @DisplayName("보호자 알림 기록 종류(서비스 상수)가 CHECK 허용 목록과 같다")
    void alertKinds() throws IOException {
        for (String kind : java.util.List.of("RISK", "ATTENTION", "MISSED")) {
            assertThat(latestCheck("chk_checkin_guardian_alert_log_kind")).contains("'" + kind + "'");
        }
    }

    private static String latestCheck(String constraint) throws IOException {
        Pattern pattern = Pattern.compile(constraint + "\\s+CHECK\\s*\\((.*?)\\)\\s*\\)?\\s*[,;\\n]", Pattern.DOTALL);
        String latest = null;
        int latestVersion = -1;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                Matcher name = Pattern.compile("V(\\d+)__").matcher(file.getFileName().toString());
                if (!name.find()) {
                    continue;
                }
                Matcher m = pattern.matcher(Files.readString(file));
                int version = Integer.parseInt(name.group(1));
                while (m.find()) {
                    if (version > latestVersion) {
                        latest = m.group(1);
                        latestVersion = version;
                    }
                }
            }
        }
        assertThat(latest).as(constraint + " CHECK 정의를 찾지 못함").isNotNull();
        return latest;
    }
}
