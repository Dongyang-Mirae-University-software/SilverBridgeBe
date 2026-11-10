package kr.silverbridge.main.global.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** 카카오·파일서버 클라이언트가 쓰는 요청 팩토리는 리다이렉트를 따라가지 않는다 (2026-11-10 전체 점검 L-4). */
class NoRedirectRequestFactoryTest {

    @Test
    @DisplayName("GET도 리다이렉트를 따라가지 않는다 - Spring 기본은 GET만 따라간다")
    void getDoesNotFollowRedirects() throws Exception {
        NoRedirectRequestFactory factory = new NoRedirectRequestFactory();
        // 연결하지 않는다 - 준비 단계의 설정만 본다
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:1/").toURL().openConnection();

        factory.prepareConnection(connection, "GET");

        assertThat(connection.getInstanceFollowRedirects()).isFalse();
    }
}
