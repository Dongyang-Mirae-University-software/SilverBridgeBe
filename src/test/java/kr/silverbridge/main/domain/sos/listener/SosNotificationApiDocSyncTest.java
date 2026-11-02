package kr.silverbridge.main.domain.sos.listener;

import io.swagger.v3.oas.annotations.Operation;
import kr.silverbridge.main.domain.sos.controller.WardSosController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/ward/sos} Swagger 설명의 알림 문구가 리스너가 실제로 만드는 문구와 같은지 고정한다
 * (2026-11-02 #433 점검 L-2). 설명에는 "최근 10분"이 문장으로 적혀 있어, 집계 창이나 문구를 바꾸고 설명을
 * 빠뜨리면 이 테스트가 실패한다.
 */
class SosNotificationApiDocSyncTest {

    private static final String WARD = "{피보호자}";

    private static String triggerSosDescription() {
        return Arrays.stream(WardSosController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("triggerSos"))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Operation.class)
                .description();
    }

    @Test
    @DisplayName("Swagger 설명의 푸시 제목·1회 본문이 리스너 문구와 같다")
    void titleAndSingleBodyMatchListener() {
        String description = triggerSosDescription();

        assertThat(description)
                .contains("\"" + SosNotificationListener.buildTitle(WARD) + "\"")
                .contains("\"" + SosNotificationListener.buildBody(WARD, 1) + "\"");
    }

    @Test
    @DisplayName("Swagger 설명의 반복 본문·집계 창(분)이 리스너 문구와 같다")
    void repeatBodyAndWindowMatchListener() {
        String description = triggerSosDescription();
        // 설명은 횟수를 N으로, 줄바꿈을 \n 글자로 적는다
        String repeatBody = SosNotificationListener.buildBody(WARD, 2)
                .replace("2번째", "N번째")
                .replace("\n", "\\n");
        String window = repeatBody.replaceAll("(?s).*최근 (\\d+)분 내.*", "$1");

        assertThat(window).matches("\\d+");
        assertThat(description)
                .contains("\"" + repeatBody + "\"")
                .contains("최근 " + window + "분 안에 2번째부터");
    }
}
