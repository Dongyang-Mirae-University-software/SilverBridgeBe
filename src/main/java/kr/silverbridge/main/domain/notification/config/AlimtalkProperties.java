package kr.silverbridge.main.domain.notification.config;

import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카카오 알림톡 설정 (application.yaml {@code notification.alimtalk.*}).
 *
 * <p>알림톡은 <b>사전 심사에서 승인된 템플릿 문구</b>만 보낼 수 있고 우리는 {@code #{변수}}만 채운다(자유 문구 불가).
 * 그래서 알림 종류(NotificationType)마다 승인 템플릿을 하나씩 매핑한다. 매핑이 없는 종류는 발송하지 않는다.</p>
 *
 * <p>변수 값은 {@link NotificationContent#data()}에서 <b>같은 이름의 키</b>로 가져온다 — 템플릿 변수
 * {@code #{wardName}} ← {@code data["wardName"]}. 새 템플릿을 붙일 때 코드 변경 없이 설정 + data 키만 맞추면 된다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "notification.alimtalk")
public class AlimtalkProperties {

    /** 알림톡 발송 전체 ON/OFF. false면 채널이 항상 스킵된다. */
    private boolean enabled = false;

    /** Solapi 발신 프로필 ID(카카오 비즈니스 채널, KA01PF…). 미설정이면 발송 불가. */
    private String pfId = "";

    /** 알림 종류 → 승인 템플릿. 키는 {@code NotificationType} 이름(예: ANOMALY_DETECTED). */
    private Map<String, Template> templates = new LinkedHashMap<>();

    /** 해당 알림 종류에 쓸 수 있는 템플릿. 미승인·미설정이면 null(→ 채널 스킵). */
    public Template templateFor(String notificationType) {
        if (!enabled || !StringUtils.hasText(pfId) || notificationType == null) {
            return null;
        }
        Template template = templates.get(notificationType);
        return (template != null && StringUtils.hasText(template.getTemplateId())) ? template : null;
    }

    @Getter
    @Setter
    public static class Template {

        /** 승인된 템플릿 ID (KA01TP…). 비어 있으면 발송하지 않는다. */
        private String templateId = "";

        /** 템플릿이 쓰는 변수 이름들. 값은 알림 data의 동명 키에서 가져온다. */
        private List<String> variables = List.of();

        /** Solapi가 요구하는 {@code "#{변수}" → 값} 맵으로 변환한다. 값이 없으면 빈 문자열(발송은 진행). */
        public Map<String, String> bindVariables(NotificationContent content) {
            Map<String, String> bound = new LinkedHashMap<>();
            Map<String, String> data = content.data() != null ? content.data() : Map.of();
            for (String name : variables) {
                bound.put("#{" + name + "}", data.getOrDefault(name, ""));
            }
            return bound;
        }
    }
}
