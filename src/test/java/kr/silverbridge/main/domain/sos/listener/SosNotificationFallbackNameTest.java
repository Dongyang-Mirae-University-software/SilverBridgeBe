package kr.silverbridge.main.domain.sos.listener;

import static org.assertj.core.api.Assertions.assertThat;

import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.global.util.ElderName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이름을 모를 때 SOS 문구가 "어르신님"이 되지 않는다(대체 이름 통일, 2026-11-22). 이름이 있으면 문구는 그대로다. */
class SosNotificationFallbackNameTest {

    @Test
    @DisplayName("대체 이름이면 '[긴급] 어르신 SOS' / '어르신이 긴급 도움을 요청했습니다.'")
    void fallbackName() {
        String name = ElderName.FALLBACK;

        assertThat(SosNotificationListener.buildTitle(name, SosTriggerType.SOS_BUTTON)).isEqualTo("[긴급] 어르신 SOS");
        assertThat(SosNotificationListener.buildBody(name, 1, SosTriggerType.SOS_BUTTON)).isEqualTo("어르신이 긴급 도움을 요청했습니다.");
        assertThat(SosNotificationListener.buildSmsFallbackText(name, 1, SosTriggerType.SOS_BUTTON))
                .isEqualTo("[긴급 SOS] 어르신이 긴급 도움을 요청했습니다.");
        assertThat(SosNotificationListener.buildTitle(name, SosTriggerType.CHATBOT_109)).isEqualTo("[긴급] 어르신 마음 위기 신호");
    }

    @Test
    @DisplayName("이름이 있으면 기존 문구 그대로 '홍길동님'")
    void realName() {
        assertThat(SosNotificationListener.buildTitle("김영희", SosTriggerType.SOS_BUTTON)).isEqualTo("[긴급] 김영희님 SOS");
        assertThat(SosNotificationListener.buildBody("김영희", 1, SosTriggerType.SOS_BUTTON)).isEqualTo("김영희님이 긴급 도움을 요청했습니다.");
    }
}
