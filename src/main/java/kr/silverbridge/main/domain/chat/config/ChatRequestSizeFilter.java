package kr.silverbridge.main.domain.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 챗 전송 요청 본문 크기 상한 - 파싱 전에 자른다(2026-10 전체 점검 M-4).
 *
 * <p>context 8KB 검사는 JSON 파싱 뒤라, 수 MB짜리 본문도 메모리에 다 올린 다음에야 거절됐다(Jackson 기본 문자열 상한은
 * 수십 MB). 인증된 사용자만 닿는 경로지만 동시 10건이면 그만큼 힙을 쓴다. {@code Content-Length}가 상한을 넘으면 바로 413,
 * 길이를 밝히지 않은 본문(chunked)은 읽는 중에 상한을 넘으면 읽기를 실패시켜 400(본문 해석 실패)이 된다.</p>
 *
 * <p>등록은 {@link ChatRequestSizeFilterConfig} - 챗 전송 두 경로에만, 보안 필터 체인 뒤에 건다(미인증 요청은 먼저 401).</p>
 */
@Slf4j
public class ChatRequestSizeFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final long maxBytes;

    public ChatRequestSizeFilter(ObjectMapper objectMapper, long maxBytes) {
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            log.warn("[CHAT-REQUEST-TOO-LARGE] 챗 요청 본문 상한 초과: bytes={}, max={}", declared, maxBytes);
            response.setStatus(ErrorCode.CHAT_REQUEST_TOO_LARGE.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(ErrorCode.CHAT_REQUEST_TOO_LARGE)));
            return;
        }
        filterChain.doFilter(declared < 0 ? new LimitedRequest(request, maxBytes) : request, response);
    }

    /** 길이를 밝히지 않은 본문을 상한까지만 읽게 한다. */
    static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = delegate.read(buffer, offset, length);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws IOException {
                    read += n;
                    if (read > maxBytes) {
                        log.warn("[CHAT-REQUEST-TOO-LARGE] 길이 미표기 챗 요청 본문 상한 초과: max={}", maxBytes);
                        throw new IOException("chat request body exceeds limit");
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
