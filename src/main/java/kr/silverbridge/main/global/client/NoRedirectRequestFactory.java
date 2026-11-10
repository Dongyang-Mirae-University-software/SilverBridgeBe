package kr.silverbridge.main.global.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * 리다이렉트를 따라가지 않는 요청 팩토리 (2026-11-10 전체 점검 L-4).
 *
 * <p>{@link SimpleClientHttpRequestFactory}는 GET 요청의 리다이렉트를 따라간다. 인증 헤더를 실은 요청이 응답 한 줄로
 * 다른 주소로 다시 나가지 않도록 끈다(AI 계열 클라이언트와 같은 기준). 3xx는 호출부에서 오류 응답으로 처리된다.</p>
 */
public class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
