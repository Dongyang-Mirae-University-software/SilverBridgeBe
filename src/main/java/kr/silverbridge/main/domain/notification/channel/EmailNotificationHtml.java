package kr.silverbridge.main.domain.notification.channel;

import org.springframework.web.util.HtmlUtils;

/**
 * 이메일 알림의 HTML 본문(2026-11-20, 디자인 B안 2026-11-21). 텍스트 본문과 같은 제목·본문·꼬리말을 받는 사람이
 * 보기 좋게 감싼다.
 *
 * <p><b>구성(B안 - 카드형)</b>: 크림 바탕 위 서비스 이름 줄(앱 색) → 흰 카드(알림 종류 칩 → 제목 → 본문) →
 * 카드 밖 회색 꼬리말. 색은 FE 앱 톤(테라코타 {@code #b0674d}·크림 {@code #fbf6f0})과 맞췄다. 상세
 * {@code docs/(2026-11-21) design-email-notification-html-style.md}.</p>
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

    private static final String PAGE_BG = "#fbf6f0";
    private static final String BRAND = "#b0674d";
    private static final String CARD_BORDER = "#eadfd6";
    private static final String CHIP_BG = "#f6e8e1";
    private static final String CHIP_TEXT = "#8a4a35";
    private static final String TITLE_TEXT = "#2b211c";
    private static final String BODY_TEXT = "#3a302b";
    private static final String FOOTER_TEXT = "#8c7f77";

    /**
     * @param serviceName 상단 서비스 이름 줄
     * @param label       알림 종류 칩 문구(null·빈 값이면 칩 생략)
     * @param title       알림 제목(null이면 제목 줄 생략). 서비스 이름과 같으면 상단 줄과 겹치므로 생략한다
     * @param body        알림 본문(null이면 빈 문자열)
     * @param footerLines 꼬리말 줄(문구는 텍스트 본문과 같다)
     */
    static String render(String serviceName, String label, String title, String body, String... footerLines) {
        StringBuilder html = new StringBuilder(3072);
        html.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(serviceName)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background-color:").append(PAGE_BG).append(";\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"background-color:").append(PAGE_BG).append(";\">")
                .append("<tr><td align=\"center\" style=\"padding:20px 12px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"max-width:560px;width:100%;font-family:").append(FONT).append(";\">")
                // 서비스 이름 줄(앱 색, 띠 없음)
                .append("<tr><td style=\"padding:0 4px 12px 4px;color:").append(BRAND)
                .append(";font-size:17px;font-weight:bold;line-height:1.4;\">")
                .append(escape(serviceName)).append("</td></tr>")
                // 카드
                .append("<tr><td style=\"background-color:#ffffff;border:1px solid ").append(CARD_BORDER)
                .append(";border-radius:14px;padding:0;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">");

        boolean hasLabel = label != null && !label.isBlank();
        if (hasLabel) {
            html.append("<tr><td style=\"padding:22px 22px 0 22px;\">")
                    .append("<span style=\"display:inline-block;background-color:").append(CHIP_BG)
                    .append(";color:").append(CHIP_TEXT)
                    .append(";font-size:14px;font-weight:bold;line-height:1;padding:7px 10px;border-radius:999px;\">")
                    .append(escape(label.strip())).append("</span></td></tr>");
        }

        boolean hasTitle = title != null && !title.isBlank() && !title.strip().equals(serviceName);
        if (hasTitle) {
            html.append("<tr><td style=\"padding:").append(hasLabel ? "14px" : "22px")
                    .append(" 22px 0 22px;color:").append(TITLE_TEXT)
                    .append(";font-size:22px;font-weight:bold;line-height:1.45;word-break:keep-all;overflow-wrap:anywhere;\">")
                    .append(escapeMultiline(title.strip())).append("</td></tr>");
        }

        html.append("<tr><td style=\"padding:").append(hasLabel || hasTitle ? "12px" : "22px")
                .append(" 22px 24px 22px;color:").append(BODY_TEXT)
                .append(";font-size:18px;line-height:1.75;word-break:keep-all;overflow-wrap:anywhere;\">")
                .append(escapeMultiline(body == null ? "" : body)).append("</td></tr>")
                .append("</table></td></tr>");

        // 꼬리말(카드 밖, 한 단계 작고 흐리게)
        html.append("<tr><td style=\"padding:16px 6px 0 6px;color:").append(FOOTER_TEXT)
                .append(";font-size:14px;line-height:1.65;word-break:keep-all;overflow-wrap:anywhere;\">");
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
