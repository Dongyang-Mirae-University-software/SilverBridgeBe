package kr.silverbridge.main.domain.companion;

import kr.silverbridge.main.domain.companion.client.CompanionMedia;
import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 한글 값 → 영문 코드(결정 D), 시간대 창, 업로드 형식 판별. */
class CompanionValueMappingTest {

    @Test
    @DisplayName("종료 이유: AI 한글 문구와 USER_STOP을 코드로 바꾸고, 모르는 값은 비운다")
    void endReason() {
        assertThat(CompanionEndReason.fromAi("최대 턴 수")).contains(CompanionEndReason.MAX_TURNS);
        assertThat(CompanionEndReason.fromAi("연속 무응답")).contains(CompanionEndReason.NO_RESPONSE);
        assertThat(CompanionEndReason.fromAi("USER_STOP")).contains(CompanionEndReason.USER_STOP);
        assertThat(CompanionEndReason.fromAi("시간 초과")).isEmpty();
        assertThat(CompanionEndReason.fromAi(null)).isEmpty();
    }

    @Test
    @DisplayName("기분: 기쁨·평온·걱정·우울만 코드가 되고, 판단 불가(null)·턴 라벨(슬픔 등)은 비운다")
    void mood() {
        assertThat(CompanionMood.fromAi("기쁨")).contains(CompanionMood.JOY);
        assertThat(CompanionMood.fromAi("평온")).contains(CompanionMood.CALM);
        assertThat(CompanionMood.fromAi("걱정")).contains(CompanionMood.WORRY);
        assertThat(CompanionMood.fromAi("우울")).contains(CompanionMood.DEPRESSED);
        assertThat(CompanionMood.fromAi(null)).isEmpty();
        assertThat(CompanionMood.fromAi("슬픔")).isEmpty();
    }

    @Test
    @DisplayName("시간대 창은 시작 시각 포함, 시작+60분 미만이다")
    void slotWindow() {
        assertThat(CompanionSlot.S09.contains(LocalTime.of(9, 0), 60)).isTrue();
        assertThat(CompanionSlot.S09.contains(LocalTime.of(9, 59, 59), 60)).isTrue();
        assertThat(CompanionSlot.S09.contains(LocalTime.of(10, 0), 60)).isFalse();
        assertThat(CompanionSlot.S09.contains(LocalTime.of(8, 59), 60)).isFalse();
        assertThat(CompanionSlot.fromCode("21:00")).contains(CompanionSlot.S21);
        assertThat(CompanionSlot.fromCode("22:00")).isEmpty();
    }

    @Test
    @DisplayName("음성은 WAV·WebM·MP3, 사진은 JPEG·PNG만 판별한다(앞부분 바이트 기준)")
    void mediaDetection() {
        assertThat(CompanionMedia.detectAudio(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E')))
                .isEqualTo(CompanionMedia.WAV);
        assertThat(CompanionMedia.detectAudio(bytes(0x1A, 0x45, 0xDF, 0xA3))).isEqualTo(CompanionMedia.WEBM);
        assertThat(CompanionMedia.detectAudio(bytes('I', 'D', '3', 4))).isEqualTo(CompanionMedia.MP3);
        assertThat(CompanionMedia.detectAudio(bytes(0xFF, 0xFB, 0x90))).isEqualTo(CompanionMedia.MP3);
        assertThat(CompanionMedia.detectAudio(bytes(0xFF, 0xD8, 0xFF))).isNull();
        assertThat(CompanionMedia.detectAudio(new byte[0])).isNull();

        assertThat(CompanionMedia.detectImage(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo(CompanionMedia.JPEG);
        assertThat(CompanionMedia.detectImage(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .isEqualTo(CompanionMedia.PNG);
        assertThat(CompanionMedia.detectImage(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isNull();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
