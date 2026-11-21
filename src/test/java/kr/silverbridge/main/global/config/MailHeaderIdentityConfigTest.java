package kr.silverbridge.main.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 메일 머리글 정체성 설정 고정 (2026-11-21). 실제 {@code application.yaml}의 {@code spring.mail.properties}로
 * {@link JavaMailSenderImpl}을 만들어, 컨테이너 ID가 메일 머리글에 새지 않는지 확인한다.
 *
 * <ul>
 *   <li>Message-ID 도메인 = 발송 계정 도메인({@code mail.from}) - 보낸 사람(From) 도메인과 맞춘다.</li>
 *   <li>EHLO 이름 = {@code mail.smtp.localhost}(기본 {@code api.devdmu.gosky.kr}, {@code MAIL_SMTP_LOCALHOST}로 바꿀 수 있음).</li>
 * </ul>
 * 이 둘이 빠지면 Message-ID·Received 머리글에 컨테이너 ID(예: {@code a8a43e5e5201})가 들어가 스팸 판정 신호가 된다.
 */
class MailHeaderIdentityConfigTest {

    private static final String PREFIX = "spring.mail.properties.";

    private static Properties javaMailProperties(Map<String, Object> env) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties raw = yaml.getObject();

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", env));

        // YAML의 true·10000은 문자열이 아닌 값으로 읽히므로 stringPropertyNames()가 아니라 전체 키를 문자열로 바꾼다.
        Properties props = new Properties();
        raw.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (name.startsWith(PREFIX)) {
                props.setProperty(name.substring(PREFIX.length()),
                        environment.resolveRequiredPlaceholders(String.valueOf(value)));
            }
        });
        return props;
    }

    @Test
    @DisplayName("Message-ID 도메인은 발송 계정 도메인이다(컨테이너 ID가 아니다)")
    void messageId_발송계정도메인() throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setJavaMailProperties(javaMailProperties(Map.of("MAIL_USERNAME", "noreply.test@gmail.com")));

        MimeMessage message = sender.createMimeMessage();
        message.setText("본문", "UTF-8");
        message.saveChanges();

        assertThat(message.getMessageID()).endsWith("@gmail.com>");
    }

    @Test
    @DisplayName("EHLO 이름은 기본 api.devdmu.gosky.kr이고 MAIL_SMTP_LOCALHOST로 바꿀 수 있다")
    void ehlo이름() {
        assertThat(javaMailProperties(Map.of("MAIL_USERNAME", "noreply.test@gmail.com"))
                .getProperty("mail.smtp.localhost")).isEqualTo("api.devdmu.gosky.kr");
        assertThat(javaMailProperties(Map.of("MAIL_USERNAME", "noreply.test@gmail.com",
                "MAIL_SMTP_LOCALHOST", "mail.example.org"))
                .getProperty("mail.smtp.localhost")).isEqualTo("mail.example.org");
    }

    @Test
    @DisplayName("기존 SMTP 설정(인증·STARTTLS·시간 제한 10초)은 그대로다")
    void 기존설정유지() {
        Properties props = javaMailProperties(Map.of("MAIL_USERNAME", "noreply.test@gmail.com"));

        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
        assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("10000");
        assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
    }
}
