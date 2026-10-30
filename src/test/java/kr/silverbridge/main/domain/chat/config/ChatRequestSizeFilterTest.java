package kr.silverbridge.main.domain.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 챗 전송 본문 상한 - 파싱 전에 자른다 (2026-10 전체 점검 M-4). */
class ChatRequestSizeFilterTest {

    private final ChatRequestSizeFilter filter = new ChatRequestSizeFilter(new ObjectMapper(), 100);

    @Test
    @DisplayName("Content-Length가 상한을 넘으면 다음 단계로 넘기지 않고 413 CHAT_REQUEST_TOO_LARGE")
    void 길이_초과_413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/guardian/chat");
        request.setContent(new byte[101]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("CHAT_REQUEST_TOO_LARGE");
        assertThat(chain.getRequest()).as("컨트롤러까지 가지 않는다").isNull();
    }

    @Test
    @DisplayName("상한 안의 본문은 그대로 통과한다")
    void 상한_이내_통과() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/guardian/chat");
        request.setContent(new byte[100]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    @DisplayName("길이를 밝히지 않은 본문은 읽는 중 상한을 넘으면 읽기가 실패한다(→ 본문 해석 실패 400)")
    void 길이_미표기_읽기중_차단() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/guardian/chat") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[101]);
        AtomicReference<ServletRequest> passed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));

        InputStream in = passed.get().getInputStream();
        assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class);
    }
}
