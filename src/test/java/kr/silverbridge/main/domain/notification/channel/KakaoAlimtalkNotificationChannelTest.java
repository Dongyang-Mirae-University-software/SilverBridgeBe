package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.config.AlimtalkProperties;
import kr.silverbridge.main.domain.notification.service.AlimtalkSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KakaoAlimtalkNotificationChannel 단위 테스트.
 *
 * 알림톡은 승인된 템플릿 문구만 보낼 수 있으므로, 검증의 핵심은 "템플릿이 없으면 보내지 않는다"와
 * "템플릿 변수를 알림 data에서 정확히 바인딩한다"이다.
 */
@ExtendWith(MockitoExtension.class)
class KakaoAlimtalkNotificationChannelTest {

    private static final String TEMPLATE_ID = "KA01TP2410020337132709999999999";

    @Mock private AlimtalkSender alimtalkSender;

    private AlimtalkProperties properties;
    private KakaoAlimtalkNotificationChannel channel;

    private final NotificationContent content = NotificationContent.of(
            "이상 상황 감지", "김순자님 댁 거실에서 화재가 감지되었습니다.",
            Map.of("type", "ANOMALY_DETECTED",
                    "wardName", "김순자",
                    "location", "거실",
                    "detectedTypeLabel", "화재"));

    @BeforeEach
    void setUp() {
        properties = new AlimtalkProperties();
        properties.setEnabled(true);
        properties.setPfId("KA01PF240930145539248iUN6bVyplGB");
        channel = new KakaoAlimtalkNotificationChannel(alimtalkSender, properties);
    }

    private void givenAnomalyTemplate() {
        AlimtalkProperties.Template template = new AlimtalkProperties.Template();
        template.setTemplateId(TEMPLATE_ID);
        template.setVariables(List.of("wardName", "location", "detectedTypeLabel"));
        properties.setTemplates(Map.of("ANOMALY_DETECTED", template));
    }

    @Test
    @DisplayName("승인 템플릿이 있으면 알림 data에서 변수를 바인딩해 발송한다")
    void 템플릿있음_변수바인딩_발송() {
        givenAnomalyTemplate();
        when(alimtalkSender.send(anyString(), anyString(), any())).thenReturn(true);

        boolean sent = channel.send(new NotificationRecipient("GD0001", "01012345678", null), content);

        assertThat(sent).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(alimtalkSender).send(eq("01012345678"), eq(TEMPLATE_ID), vars.capture());
        assertThat(vars.getValue())
                .containsEntry("#{wardName}", "김순자")
                .containsEntry("#{location}", "거실")
                .containsEntry("#{detectedTypeLabel}", "화재");
    }

    @Test
    @DisplayName("해당 알림 종류의 승인 템플릿이 없으면 발송하지 않는다(자유 문구 발송은 불가)")
    void 템플릿없음_미발송() {
        // 템플릿 매핑 없음 — 승인 전에 다른 템플릿으로 억지 발송하면 문구가 어긋나 채널 제재 대상이 된다.
        boolean sent = channel.send(new NotificationRecipient("GD0001", "01012345678", null), content);

        assertThat(sent).isFalse();
        verifyNoInteractions(alimtalkSender);
    }

    @Test
    @DisplayName("설정이 꺼져 있으면 발송하지 않는다")
    void 설정OFF_미발송() {
        givenAnomalyTemplate();
        properties.setEnabled(false);

        boolean sent = channel.send(new NotificationRecipient("GD0001", "01012345678", null), content);

        assertThat(sent).isFalse();
        verifyNoInteractions(alimtalkSender);
    }

    @Test
    @DisplayName("전화번호가 없으면 발송하지 않는다(알림톡은 번호로 수신자를 찾는다)")
    void 전화번호없음_미발송() {
        givenAnomalyTemplate();

        boolean sent = channel.send(new NotificationRecipient("GD0001", null, null), content);

        assertThat(sent).isFalse();
        verifyNoInteractions(alimtalkSender);
    }
}
