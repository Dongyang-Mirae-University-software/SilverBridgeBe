package kr.silverbridge.main.domain.notification.channel;

import org.springframework.web.util.HtmlUtils;

/**
 * 이메일 알림의 HTML 본문(2026-11-20). 텍스트 본문과 같은 제목·본문·꼬리말을 받는 사람이 보기 좋게 감싼다.
 *
 * <ul>
 *   <li>인라인 스타일과 표 레이아웃만 쓴다(메일 앱 대부분이 {@code <style>}·외부 CSS를 지우거나 무시한다).</li>
 *   <li>외부 이미지·폰트·링크·추적 픽셀을 넣지 않는다 - 메일 앱이 막고, 넣으면 열람 추적이 된다.</li>
 *   <li>시니어 독자 기준 큰 글씨(제목 22px·본문 18px), 폭은 최대 560px에 휴대폰(약 360px)에서는 화면 폭을 따른다.</li>
 *   <li>제목·본문은 사용자 입력(보호자·피보호자 이름)을 담으므로 <b>모두 HTML 이스케이프</b>한 뒤 줄바꿈만 {@code <br>}로 바꾼다.</li>
 * </ul>
 */
final class EmailNotificationHtml {

    private EmailNotificationHtml() {
    }

    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',sans-serif";

    /**
     * @param serviceName 상단 띠에 쓸 서비스 이름
     * @param title       알림 제목(null이면 제목 줄 생략). 서비스 이름과 같으면 띠와 겹치므로 생략한다
     * @param body        알림 본문(null이면 빈 문자열)
     * @param footerLines 꼬리말 줄(문구는 텍스트 본문과 같다)
     */
    static String render(String serviceName, String title, String body, String... footerLines) {
        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(serviceName)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background-color:#f3f5f4;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"background-color:#f3f5f4;\"><tr><td align=\"center\" style=\"padding:16px 8px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"max-width:560px;width:100%;background-color:#ffffff;border-radius:12px;")
                .append("border-collapse:separate;overflow:hidden;font-family:").append(FONT).append(";\">")
                // 서비스 이름 띠
                .append("<tr><td style=\"background-color:#2e7d5b;padding:16px 20px;color:#ffffff;")
                .append("font-size:20px;font-weight:bold;line-height:1.4;\">")
                .append(escape(serviceName)).append("</td></tr>");

        if (title != null && !title.isBlank() && !title.strip().equals(serviceName)) {
            html.append("<tr><td style=\"padding:24px 20px 8px 20px;color:#1a1a1a;font-size:22px;")
                    .append("font-weight:bold;line-height:1.45;word-break:keep-all;overflow-wrap:anywhere;\">")
                    .append(escapeMultiline(title.strip())).append("</td></tr>");
        }

        html.append("<tr><td style=\"padding:12px 20px 24px 20px;color:#222222;font-size:18px;line-height:1.7;")
                .append("word-break:keep-all;overflow-wrap:anywhere;\">")
                .append(escapeMultiline(body == null ? "" : body)).append("</td></tr>");

        html.append("<tr><td style=\"padding:16px 20px 20px 20px;border-top:1px solid #e3e7e5;color:#666666;")
                .append("font-size:15px;line-height:1.6;word-break:keep-all;overflow-wrap:anywhere;\">");
        for (int i = 0; i < footerLines.length; i++) {
            if (i > 0) {
                html.append("<br>");
            }
            html.append(escape(footerLines[i]));
        }
        html.append("</td></tr></table></td></tr></table></body></html>");
        return html.toString();
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }

    /** 이스케이프 후 줄바꿈을 {@code <br>}로 바꾼다(줄바꿈 유지). */
    private static String escapeMultiline(String value) {
        return escape(value).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }
}
