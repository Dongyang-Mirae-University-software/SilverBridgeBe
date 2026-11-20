package kr.silverbridge.main.domain.notification.channel;

import static kr.silverbridge.main.domain.notification.channel.EmailNotificationChannel.FOOTER_LINE_1;
import static kr.silverbridge.main.domain.notification.channel.EmailNotificationChannel.FOOTER_LINE_2;
import static kr.silverbridge.main.domain.notification.channel.EmailNotificationChannel.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class EmailNotificationHtmlTest {

    private static String render(String title, String body) {
        return EmailNotificationHtml.render(SERVICE_NAME, title, body, FOOTER_LINE_1, FOOTER_LINE_2);
    }

    @Test
    @DisplayName("띠·제목·본문·꼬리말 두 줄이 순서대로 들어간다")
    void 구성() {
        String html = render("연결 수락", "피보호자가 연결 요청을 수락했습니다.");

        assertThat(html).startsWith("<!DOCTYPE html>").contains("lang=\"ko\"", "charset=\"UTF-8\"");
        int band = html.indexOf(">" + SERVICE_NAME + "</td>");
        int title = html.indexOf("연결 수락");
        int body = html.indexOf("피보호자가 연결 요청을 수락했습니다.");
        int footer = html.indexOf(FOOTER_LINE_1 + "<br>" + FOOTER_LINE_2);
        assertThat(band).isPositive();
        assertThat(title).isGreaterThan(band);
        assertThat(body).isGreaterThan(title);
        assertThat(footer).isGreaterThan(body);
    }

    @Test
    @DisplayName("본문 줄바꿈(\\n·\\r\\n)은 <br>로 유지한다")
    void 줄바꿈유지() {
        String html = render("제목", "첫 줄\n둘째 줄\r\n셋째 줄");

        assertThat(html).contains("첫 줄<br>둘째 줄<br>셋째 줄");
    }

    @Test
    @DisplayName("제목·본문의 HTML 특수문자는 모두 이스케이프한다")
    void 이스케이프() {
        String html = render("<img src=x onerror=alert(1)>", "\"따옴표\" & '작은따옴표' <script>");

        assertThat(html).doesNotContain("<img", "<script>")
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("&quot;따옴표&quot; &amp; &#39;작은따옴표&#39; &lt;script&gt;");
    }

    @Test
    @DisplayName("외부 이미지·링크·스타일시트·스크립트를 넣지 않는다(열람 추적·차단 방지)")
    void 외부자원없음() {
        String html = render("연결 수락", "본문");

        assertThat(html).doesNotContain("<img", "<a ", "href=", "src=", "<link", "<script", "<style", "http://", "https://");
    }

    @Test
    @DisplayName("휴대폰 폭에서 깨지지 않게 고정 폭 대신 max-width + width:100%를 쓴다")
    void 반응형폭() {
        String html = render("연결 수락", "본문");

        assertThat(html).contains("max-width:560px;width:100%").contains("name=\"viewport\"")
                .doesNotContainPattern("width=\"\\d{3,}\"");
    }

    @Test
    @DisplayName("제목이 null이거나 서비스 이름과 같으면 제목 줄을 생략한다")
    void 제목줄생략() {
        assertThat(render(null, "본문")).doesNotContain("font-size:22px");
        assertThat(render(SERVICE_NAME, "본문")).doesNotContain("font-size:22px");
        assertThat(render("연결 수락", "본문")).contains("font-size:22px");
    }

    /**
     * 렌더 확인용: {@code EMAIL_RENDER_DIR} 환경변수가 있을 때만 대표 3종을 파일로 남긴다(커밋 대상 아님).
     * 문구는 각 리스너·서비스의 실제 문구다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "EMAIL_RENDER_DIR", matches = ".+")
    void 렌더파일출력() throws IOException {
        Path dir = Path.of(System.getenv("EMAIL_RENDER_DIR"));
        Files.createDirectories(dir);
        write(dir, "connection-accepted.html", "연결 수락", "피보호자가 연결 요청을 수락했습니다.");
        write(dir, "inquiry-answered.html", "문의 답변 완료", "문의하신 내용에 답변이 등록되었습니다.");
        write(dir, "medication-missed.html", "오늘 복약 확인 · 홍길동님",
                "21:00까지 예정된 복약 3건 중 1건이 체크되지 않았습니다.");
        write(dir, "ward-connection-request.html", SERVICE_NAME, "김보호님이 가족 연결을 요청했어요. 앱에서 확인해 주세요.");
    }

    private static void write(Path dir, String name, String title, String body) throws IOException {
        Files.writeString(dir.resolve(name), render(title, body), StandardCharsets.UTF_8);
    }
}
